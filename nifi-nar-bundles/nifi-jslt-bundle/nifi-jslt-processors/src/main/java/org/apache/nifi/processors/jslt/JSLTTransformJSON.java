/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.jslt;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schibsted.spt.data.jslt.Expression;
import com.schibsted.spt.data.jslt.JsltException;
import com.schibsted.spt.data.jslt.Parser;
import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnShutdown;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.ValidationContext;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.ProcessorInitializationContext;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.util.StopWatch;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@EventDriven
@SideEffectFree
@SupportsBatching
@Tags({"json", "jslt", "transform"})
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@WritesAttribute(attribute = "mime.type", description = "Always set to application/json")
@CapabilityDescription("Applies a JSLT transformation to the flowfile JSON payload. A new FlowFile is created "
        + "with transformed content and is routed to the 'success' relationship. If the JSON transform "
        + "fails, the original FlowFile is routed to the 'failure' relationship.")
public class JSLTTransformJSON extends AbstractProcessor {

    public static final PropertyDescriptor JSLT_TRANSFORM = new PropertyDescriptor.Builder()
            .name("jslt-transform")
            .displayName("JSLT Transformation")
            .description("JSLT Transformation for transform of JSON data. Any NiFi Expression Language present will be evaluated first to get the final transform to be applied.")
            .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor PRETTY_PRINT = new PropertyDescriptor.Builder()
            .name("pretty_print")
            .displayName("Pretty Print")
            .description("Apply pretty-print formatting to the output of the JSLT transform")
            .required(true)
            .allowableValues("true", "false")
            .defaultValue("false")
            .build();

    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("The FlowFile with transformed content will be routed to this relationship")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("If a FlowFile fails processing for any reason (for example, the FlowFile is not valid JSON), it will be routed to this relationship")
            .build();

    private List<PropertyDescriptor> descriptors;
    private Set<Relationship> relationships;
    private static final JsonFactory jsonFactory = new JsonFactory();
    private static final ObjectMapper codec = new ObjectMapper();
    private final static String DEFAULT_CHARSET = "UTF-8";

    private final AtomicReference<Expression> precompiledExpression = new AtomicReference<>();

    @Override
    protected void init(final ProcessorInitializationContext context) {
        final List<PropertyDescriptor> descriptors = new ArrayList<>();
        descriptors.add(JSLT_TRANSFORM);
        descriptors.add(PRETTY_PRINT);
        this.descriptors = Collections.unmodifiableList(descriptors);

        final Set<Relationship> relationships = new HashSet<>();
        relationships.add(REL_SUCCESS);
        relationships.add(REL_FAILURE);
        this.relationships = Collections.unmodifiableSet(relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return descriptors;
    }

    @Override
    protected Collection<ValidationResult> customValidate(ValidationContext validationContext) {
        final List<ValidationResult> results = new ArrayList<>(super.customValidate(validationContext));

        // If no EL present, pre-compile the script (and report any errors as to mark the processor invalid)
        if (!validationContext.getProperty(JSLT_TRANSFORM).isExpressionLanguagePresent()) {
            final String transform = validationContext.getProperty(JSLT_TRANSFORM).getValue();
            try {
                precompiledExpression.set(Parser.compileString(transform));
            } catch (JsltException je) {
                results.add(new ValidationResult.Builder().subject(JSLT_TRANSFORM.getDisplayName()).valid(false).explanation("error in transform: " + je.getMessage()).build());
            }
        } else {
            // Expression Language is present, we won't know if the transform is valid until the EL is evaluated
            results.add(new ValidationResult.Builder().subject(JSLT_TRANSFORM.getDisplayName()).valid(true).build());
        }
        return results;

    }

    @OnScheduled
    public void onScheduled(final ProcessContext context) {
        // Precompile the transform if it hasn't been done already (and if there is no Expression Language present)
        if (!context.getProperty(JSLT_TRANSFORM).isExpressionLanguagePresent()) {
            final String transform = context.getProperty(JSLT_TRANSFORM).getValue();
            try {
                precompiledExpression.set(Parser.compileString(transform));
            } catch (JsltException je) {
                throw new ProcessException("Error compiling JSLT transform: " + je.getMessage(), je);
            }
        }
    }

    @Override
    public void onTrigger(final ProcessContext context, final ProcessSession session) throws ProcessException {
        final FlowFile original = session.get();
        if (original == null) {
            return;
        }

        final ComponentLog logger = getLogger();
        final StopWatch stopWatch = new StopWatch(true);

        JsonNode firstJsonNode;
        try (final InputStream in = session.read(original)) {
            try {
                JsonParser jsonParser = jsonFactory.createParser(in);
                jsonParser.setCodec(codec);

                JsonToken token = jsonParser.nextToken();
                if (token == JsonToken.START_ARRAY) {
                    token = jsonParser.nextToken(); // advance to START_OBJECT token
                }

                if (token == JsonToken.START_OBJECT) { // could be END_ARRAY also
                    firstJsonNode = jsonParser.readValueAsTree();
                } else {
                    firstJsonNode = null;
                }
            } catch (final JsonParseException e) {
                throw new IOException("Could not parse data as JSON", e);
            }
        } catch (final Exception e) {
            logger.error("Failed to transform {}; routing to failure", new Object[]{original}, e);
            session.transfer(original, REL_FAILURE);
            return;
        }

        final String jsonString;
        try {
            Expression jsltExpression = precompiledExpression.get();

            if (jsltExpression == null) {
                final String transform = context.getProperty(JSLT_TRANSFORM).evaluateAttributeExpressions(original).getValue();
                jsltExpression = Parser.compileString(transform);
            }
            final JsonNode transformedJson = jsltExpression.apply(firstJsonNode);
            if (transformedJson == null) {
                jsonString = "";
                logger.info("JSLT transform resulted in no data!");
            } else {
                jsonString = context.getProperty(PRETTY_PRINT).asBoolean() ? transformedJson.toPrettyString() : transformedJson.toString();
            }
        } catch (final Exception ex) {
            logger.error("Unable to transform {} due to {}", new Object[]{original, ex.toString()}, ex);
            session.transfer(original, REL_FAILURE);
            return;
        }

        FlowFile transformed = session.write(original, out -> out.write(jsonString.getBytes(DEFAULT_CHARSET)));

        final String transformType = context.getProperty(JSLT_TRANSFORM).getValue();
        transformed = session.putAttribute(transformed, CoreAttributes.MIME_TYPE.key(), "application/json");
        session.transfer(transformed, REL_SUCCESS);
        session.getProvenanceReporter().modifyContent(transformed, "Modified With " + transformType, stopWatch.getElapsed(TimeUnit.MILLISECONDS));
        logger.debug("Transformed {}", original);
    }

    @OnStopped
    @OnShutdown
    public void onStopped(ProcessContext context) {
        precompiledExpression.set(null);
    }
}
