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
package org.apache.nifi.processors.hive;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.dbcp.hive.Hive_1_1DBCPService;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.RecordReader;
import org.apache.nifi.serialization.SimpleRecordSchema;
import org.apache.nifi.serialization.record.MockRecordParser;
import org.apache.nifi.serialization.record.RecordField;
import org.apache.nifi.serialization.record.RecordFieldType;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(JUnit4.class)
public class TestUpdateHive_1_1Table {

    private static final String TEST_CONF_PATH = "src/test/resources/core-site.xml";
    private static final String TARGET_HIVE = "target/hive";

    private static final String[] SHOW_TABLES_COLUMN_NAMES = new String[]{"tab_name"};
    private static final String[][] SHOW_TABLES_RESULTSET = new String[][]{
            new String[]{"messages"},
            new String[]{"users"},
    };

    private static final String[] DESC_MESSAGES_TABLE_COLUMN_NAMES = new String[]{"id", "msg"};
    private static final String[][] DESC_MESSAGES_TABLE_RESULTSET = new String[][]{
            new String[]{"# col_name", "data_type", "comment"},
            new String[]{"id", "int", ""},
            new String[]{"msg", "string", ""},
            new String[]{"", null, null},
            new String[]{"# Partition Information", null, null},
            new String[]{"# col_name", "data_type", "comment"},
            new String[]{"continent", "string", ""},
            new String[]{"country", "string", ""},
            new String[]{"", null, null},
            new String[]{"# Detailed Table Information", null, null},
            new String[]{"Location:", "hdfs://mycluster:8020/warehouse/tablespace/managed/hive/messages", null}
    };

    private static final String[] DESC_USERS_TABLE_COLUMN_NAMES = new String[]{"name", "favorite_number", "favorite_color", "scale"};
    private static final String[][] DESC_USERS_TABLE_RESULTSET = new String[][]{
            new String[]{"name", "string", ""},
            new String[]{"favorite_number", "int", ""},
            new String[]{"favorite_color", "string", ""},
            new String[]{"scale", "double", ""},
            new String[]{"", null, null},
            new String[]{"# Detailed Table Information", null, null},
            new String[]{"Location:", "hdfs://mycluster:8020/warehouse/tablespace/managed/hive/users", null}
    };

    private static final String[] DESC_NEW_TABLE_COLUMN_NAMES = DESC_USERS_TABLE_COLUMN_NAMES;
    private static final String[][] DESC_NEW_TABLE_RESULTSET = new String[][]{
            new String[]{"", null, null},
            new String[]{"name", "string", ""},
            new String[]{"favorite_number", "int", ""},
            new String[]{"favorite_color", "string", ""},
            new String[]{"scale", "double", ""},
            new String[]{"", null, null},
            new String[]{"# Detailed Table Information", null, null},
            new String[]{"Location:", "hdfs://mycluster:8020/warehouse/tablespace/managed/hive/newTable", null}
    };

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestRunner runner;
    private UpdateHive_1_1Table processor;

    @Before
    public void setUp() {

        Configuration testConf = new Configuration();
        testConf.addResource(new Path(TEST_CONF_PATH));

        // Delete any temp files from previous tests
        try {
            FileUtils.deleteDirectory(new File(TARGET_HIVE));
        } catch (IOException ioe) {
            // Do nothing, directory may not have existed
        }

        processor = new UpdateHive_1_1Table();
    }

    private void configure(final UpdateHive_1_1Table processor, final int numUsers) throws InitializationException {
        configure(processor, numUsers, false, -1);
    }

    private void configure(final UpdateHive_1_1Table processor, final int numUsers, boolean failOnCreateReader, int failAfter) throws InitializationException {
        configure(processor, numUsers, failOnCreateReader, failAfter, null);
    }

    private void configure(final UpdateHive_1_1Table processor, final int numUsers, final boolean failOnCreateReader, final int failAfter,
                           final BiFunction<Integer, MockRecordParser, Void> recordGenerator) throws InitializationException {
        runner = TestRunners.newTestRunner(processor);
        MockRecordParser readerFactory = new MockRecordParser() {
            @Override
            public RecordReader createRecordReader(Map<String, String> variables, InputStream in, long inputLength, ComponentLog logger) throws IOException, SchemaNotFoundException {
                if (failOnCreateReader) {
                    throw new SchemaNotFoundException("test");
                }
                return super.createRecordReader(variables, in, inputLength, logger);
            }
        };
        List<RecordField> fields = Arrays.asList(
                new RecordField("name", RecordFieldType.STRING.getDataType()),
                new RecordField("favorite_number", RecordFieldType.INT.getDataType()),
                new RecordField("favorite_color", RecordFieldType.STRING.getDataType()),
                new RecordField("scale", RecordFieldType.DOUBLE.getDataType())
        );
        final SimpleRecordSchema recordSchema = new SimpleRecordSchema(fields);
        for (final RecordField recordField : recordSchema.getFields()) {
            readerFactory.addSchemaField(recordField.getFieldName(), recordField.getDataType().getFieldType(), recordField.isNullable());
        }

        if (recordGenerator == null) {
            for (int i = 0; i < numUsers; i++) {
                readerFactory.addRecord("name" + i, i, "blue" + i, i * 10.0);
            }
        } else {
            recordGenerator.apply(numUsers, readerFactory);
        }

        readerFactory.failAfter(failAfter);

        runner.addControllerService("mock-reader-factory", readerFactory);
        runner.enableControllerService(readerFactory);

        runner.setProperty(UpdateHive_1_1Table.RECORD_READER, "mock-reader-factory");
    }

    @Test
    public void testSetup() throws Exception {
        configure(processor, 0);
        runner.assertNotValid();
        final File tempDir = folder.getRoot();
        final File dbDir = new File(tempDir, "db");
        final DBCPService service = new MockDBCPService(dbDir.getAbsolutePath());
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);
        runner.setProperty(UpdateHive_1_1Table.HIVE_DBCP_SERVICE, "dbcp");
        runner.assertNotValid();
        runner.setProperty(UpdateHive_1_1Table.DB_NAME, "default");
        runner.assertNotValid();
        runner.setProperty(UpdateHive_1_1Table.TABLE_NAME, "users");
        runner.assertValid();
        runner.run();
    }


    @Test
    public void testNoStatementsExecuted() throws Exception {
        configure(processor, 1);
        runner.setProperty(UpdateHive_1_1Table.DB_NAME, "default");
        runner.setProperty(UpdateHive_1_1Table.TABLE_NAME, "users");
        final MockDBCPService service = new MockDBCPService("test");
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);
        runner.setProperty(UpdateHive_1_1Table.HIVE_DBCP_SERVICE, "dbcp");
        runner.setProperty(UpdateHive_1_1Table.STATIC_PARTITION_VALUES, "Asia,China");
        runner.enqueue(new byte[0]);
        runner.run();

        runner.assertTransferCount(UpdateHive_1_1Table.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(UpdateHive_1_1Table.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals(UpdateHive_1_1Table.ATTR_OUTPUT_TABLE, "default.users");
        flowFile.assertAttributeEquals(UpdateHive_1_1Table.ATTR_OUTPUT_PATH, "hdfs://mycluster:8020/warehouse/tablespace/managed/hive/users");
        assertTrue(service.getExecutedStatements().isEmpty());
    }

    @Test
    public void testCreateTable() throws Exception {
        configure(processor, 1);
        runner.setProperty(UpdateHive_1_1Table.DB_NAME, "${db.name}");
        runner.setProperty(UpdateHive_1_1Table.TABLE_NAME, "${table.name}");
        runner.setProperty(UpdateHive_1_1Table.CREATE_TABLE, UpdateHive_1_1Table.CREATE_IF_NOT_EXISTS);
        runner.setProperty(UpdateHive_1_1Table.TABLE_STORAGE_FORMAT, UpdateHive_1_1Table.PARQUET);
        final MockDBCPService service = new MockDBCPService("newTable");
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);
        runner.setProperty(UpdateHive_1_1Table.HIVE_DBCP_SERVICE, "dbcp");
        Map<String, String> attrs = new HashMap<>();
        attrs.put("db.name", "default");
        attrs.put("table.name", "newTable");
        runner.enqueue(new byte[0], attrs);
        runner.run();

        runner.assertTransferCount(UpdateHive_1_1Table.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(UpdateHive_1_1Table.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals(UpdateHive_1_1Table.ATTR_OUTPUT_TABLE, "default.newTable");
        flowFile.assertAttributeEquals(UpdateHive_1_1Table.ATTR_OUTPUT_PATH, "hdfs://mycluster:8020/warehouse/tablespace/managed/hive/newTable");
        List<String> statements = service.getExecutedStatements();
        assertEquals(1, statements.size());
        assertEquals("CREATE TABLE IF NOT EXISTS newTable (name STRING, favorite_number INT, favorite_color STRING, scale DOUBLE) STORED AS PARQUET",
                statements.get(0));
    }

    @Test
    public void testAddColumnsAndPartition() throws Exception {
        configure(processor, 1);
        runner.setProperty(UpdateHive_1_1Table.DB_NAME, "default");
        runner.setProperty(UpdateHive_1_1Table.TABLE_NAME, "messages");
        final MockDBCPService service = new MockDBCPService("test");
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);
        runner.setProperty(UpdateHive_1_1Table.HIVE_DBCP_SERVICE, "dbcp");
        runner.setProperty(UpdateHive_1_1Table.STATIC_PARTITION_VALUES, "Asia,China");
        runner.enqueue(new byte[0]);
        runner.run();

        runner.assertTransferCount(UpdateHive_1_1Table.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(UpdateHive_1_1Table.REL_SUCCESS).get(0);
        flowFile.assertAttributeEquals(UpdateHive_1_1Table.ATTR_OUTPUT_TABLE, "default.messages");
        flowFile.assertAttributeEquals(UpdateHive_1_1Table.ATTR_OUTPUT_PATH,
                "hdfs://mycluster:8020/warehouse/tablespace/managed/hive/messages/continent=Asia/country=China");
        List<String> statements = service.getExecutedStatements();
        assertEquals(2, statements.size());
        // All columns from users table/data should be added to the table, and a new partition should be added
        assertEquals("ALTER TABLE messages ADD COLUMNS (name STRING, favorite_number INT, favorite_color STRING, scale DOUBLE)",
                statements.get(0));
        assertEquals("ALTER TABLE messages ADD IF NOT EXISTS PARTITION (continent='Asia', country='China')",
                statements.get(1));
    }

    @Test
    public void testMissingPartitionValues() throws Exception {
        configure(processor, 1);
        runner.setProperty(UpdateHive_1_1Table.DB_NAME, "default");
        runner.setProperty(UpdateHive_1_1Table.TABLE_NAME, "messages");
        final DBCPService service = new MockDBCPService("test");
        runner.addControllerService("dbcp", service);
        runner.enableControllerService(service);
        runner.setProperty(UpdateHive_1_1Table.HIVE_DBCP_SERVICE, "dbcp");
        runner.enqueue(new byte[0]);
        runner.run();

        runner.assertTransferCount(UpdateHive_1_1Table.REL_SUCCESS, 0);
        runner.assertTransferCount(UpdateHive_1_1Table.REL_FAILURE, 1);
    }

    /**
     * Simple implementation only for testing purposes
     */
    private static class MockDBCPService extends AbstractControllerService implements Hive_1_1DBCPService {
        private final String dbLocation;

        private final List<String> executedStatements = new ArrayList<>();

        MockDBCPService(final String dbLocation) {
            this.dbLocation = dbLocation;
        }

        @Override
        public String getIdentifier() {
            return "dbcp";
        }

        @Override
        public Connection getConnection() throws ProcessException {
            try {
                Connection conn = mock(Connection.class);
                Statement s = mock(Statement.class);
                when(conn.createStatement()).thenReturn(s);
                when(s.executeQuery(anyString())).thenAnswer((Answer<ResultSet>) invocation -> {
                    final String query = (String) invocation.getArguments()[0];
                    if ("SHOW TABLES".equals(query)) {
                        return new MockResultSet(SHOW_TABLES_COLUMN_NAMES, SHOW_TABLES_RESULTSET).createResultSet();
                    } else if ("DESC FORMATTED messages".equals(query)) {
                        return new MockResultSet(DESC_MESSAGES_TABLE_COLUMN_NAMES, DESC_MESSAGES_TABLE_RESULTSET).createResultSet();
                    } else if ("DESC FORMATTED users".equals(query)) {
                        return new MockResultSet(DESC_USERS_TABLE_COLUMN_NAMES, DESC_USERS_TABLE_RESULTSET).createResultSet();
                    } else if ("DESC FORMATTED newTable".equals(query)) {
                        return new MockResultSet(DESC_NEW_TABLE_COLUMN_NAMES, DESC_NEW_TABLE_RESULTSET).createResultSet();
                    } else {
                        return new MockResultSet(new String[]{}, new String[][]{new String[]{}}).createResultSet();
                    }
                });
                when(s.execute(anyString())).thenAnswer((Answer<Boolean>) invocation -> {
                    executedStatements.add((String) invocation.getArguments()[0]);
                    return false;
                });
                return conn;
            } catch (final Exception e) {
                e.printStackTrace();
                throw new ProcessException("getConnection failed: " + e);
            }
        }

        @Override
        public String getConnectionURL() {
            return "jdbc:fake:" + dbLocation;
        }

        List<String> getExecutedStatements() {
            return executedStatements;
        }
    }

    private static class MockResultSet {
        String[] colNames;
        String[][] data;
        int currentRow;

        MockResultSet(String[] colNames, String[][] data) {
            this.colNames = colNames;
            this.data = data;
            currentRow = 0;
        }

        ResultSet createResultSet() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.next()).thenAnswer((Answer<Boolean>) invocation -> (data != null) && (++currentRow <= data.length));
            when(rs.getString(anyInt())).thenAnswer((Answer<String>) invocation -> {
                final int index = (int) invocation.getArguments()[0];
                if (index < 1) {
                    throw new SQLException("Columns start with index 1");
                }
                if (currentRow > data.length) {
                    throw new SQLException("This result set is already closed");
                }
                return data[currentRow - 1][index - 1];
            });

            return rs;
        }
    }
}