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
package org.apache.nifi.rules.engine;

import org.apache.nifi.annotation.lifecycle.OnEnabled;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.controller.ConfigurationContext;
import org.apache.nifi.controller.ControllerServiceInitializationContext;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.rules.Action;
import org.apache.nifi.rules.ActionHandler;
import org.apache.nifi.rules.Rule;
import org.apache.nifi.rules.RulesFactory;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.api.RulesEngine;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.jeasy.rules.core.RuleBuilder;
import org.jeasy.rules.mvel.MVELCondition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class EasyRulesEngineService  extends AbstractControllerService implements RulesEngineService {

    static final AllowableValue YAML = new AllowableValue("YAML", "YAML", "YAML file configuration type.");
    static final AllowableValue JSON = new AllowableValue("JSON", "JSON", "JSON file configuration type.");

    static final AllowableValue NIFI = new AllowableValue("NIFI", "NIFI", "NIFI rules formatted file.");

    static final PropertyDescriptor RULES_FILE_PATH = new PropertyDescriptor.Builder()
            .name("rules-file-path")
            .displayName("Rules File Path")
            .description("Rules File Location")
            .required(true)
            .addValidator(StandardValidators.FILE_EXISTS_VALIDATOR)
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .build();

    static final PropertyDescriptor RULES_FILE_TYPE = new PropertyDescriptor.Builder()
            .name("rules-file-type")
            .displayName("Rules File Type")
            .description("File type for rules definition. Supported file types are YAML and JSON")
            .required(true)
            .allowableValues(JSON,YAML)
            .defaultValue(JSON.getValue())
            .build();

    static final PropertyDescriptor RULES_FILE_FORMAT = new PropertyDescriptor.Builder()
            .name("rules-file-format")
            .displayName("Rules File Format")
            .description("File format for rules. Supported formats are NiFi Rules.")
            .required(true)
            .allowableValues(NIFI)
            .defaultValue(NIFI.getValue())
            .build();


    protected List<PropertyDescriptor> properties;
    protected volatile List<Rule> rules;

    @Override
    protected void init(ControllerServiceInitializationContext config) throws InitializationException {
        super.init(config);
        final List<PropertyDescriptor> properties = new ArrayList<>();
        properties.add(RULES_FILE_TYPE);
        properties.add(RULES_FILE_PATH);
        properties.add(RULES_FILE_FORMAT);
        this.properties = Collections.unmodifiableList(properties);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return properties;
    }

    @OnEnabled
    public void onEnabled(final ConfigurationContext context) throws InitializationException {
        final String rulesFile = context.getProperty(RULES_FILE_PATH).getValue();
        final String rulesFileType = context.getProperty(RULES_FILE_TYPE).getValue();
        try{
            rules = RulesFactory.createRules(rulesFile, rulesFileType);
        } catch (Exception fex){
            throw new InitializationException(fex);
        }
    }

    @Override
    public List<Action> fireRules(Map<String, Object> facts) {
        final List<Action> actions = new ArrayList<>();
        if(rules == null){
            return null;
        }else {
            List<Rule> filteredRules = filterRulesByFacts(rules, facts);
            if(filteredRules == null){
                return null;
            }else{
                org.jeasy.rules.api.Rules easyRules = getRules(filteredRules, actions::add);
                Facts easyFacts = new Facts();
                facts.forEach(easyFacts::put);
                RulesEngine rulesEngine = new DefaultRulesEngine();
                rulesEngine.fire(easyRules, easyFacts);
                return actions;
            }
        }
    }

    protected List<Rule> filterRulesByFacts(List<Rule> rules, Map<String, Object> facts){
        final Set<String> factNames = facts.keySet();
        Predicate<Rule> rulePredicate = rule -> factNames.containsAll(rule.getFacts());
        return  rules.stream().filter(rulePredicate).collect(Collectors.toList());
    }

    protected Rules getRules(List<Rule> rules, ActionHandler actionHandler) {
        final Rules easyRules = new Rules();
        rules.forEach(rule -> {
            RuleBuilder ruleBuilder = new RuleBuilder();
            MVELCondition condition = new MVELCondition(rule.getCondition());
            ruleBuilder.name(rule.getName())
                    .description(rule.getDescription())
                    .priority(rule.getPriority())
                    .when(condition);
            for (Action action : rule.getActions()) {
                ruleBuilder.then(facts -> {
                    actionHandler.execute(action);
                });
            }
            easyRules.register(ruleBuilder.build());
        });
        return easyRules;
    }

}
