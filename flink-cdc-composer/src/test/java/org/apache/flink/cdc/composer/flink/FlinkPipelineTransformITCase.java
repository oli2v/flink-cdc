/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.cdc.composer.flink;

import org.apache.flink.cdc.common.configuration.Configuration;
import org.apache.flink.cdc.common.data.binary.BinaryRecordData;
import org.apache.flink.cdc.common.data.binary.BinaryStringData;
import org.apache.flink.cdc.common.event.AddColumnEvent;
import org.apache.flink.cdc.common.event.AlterColumnTypeEvent;
import org.apache.flink.cdc.common.event.CreateTableEvent;
import org.apache.flink.cdc.common.event.DataChangeEvent;
import org.apache.flink.cdc.common.event.DropColumnEvent;
import org.apache.flink.cdc.common.event.Event;
import org.apache.flink.cdc.common.event.RenameColumnEvent;
import org.apache.flink.cdc.common.event.TableId;
import org.apache.flink.cdc.common.pipeline.PipelineOptions;
import org.apache.flink.cdc.common.pipeline.SchemaChangeBehavior;
import org.apache.flink.cdc.common.schema.Column;
import org.apache.flink.cdc.common.schema.Schema;
import org.apache.flink.cdc.common.types.DataType;
import org.apache.flink.cdc.common.types.DataTypes;
import org.apache.flink.cdc.composer.PipelineExecution;
import org.apache.flink.cdc.composer.definition.PipelineDef;
import org.apache.flink.cdc.composer.definition.SinkDef;
import org.apache.flink.cdc.composer.definition.SourceDef;
import org.apache.flink.cdc.composer.definition.TransformDef;
import org.apache.flink.cdc.connectors.values.ValuesDatabase;
import org.apache.flink.cdc.connectors.values.factory.ValuesDataFactory;
import org.apache.flink.cdc.connectors.values.sink.ValuesDataSink;
import org.apache.flink.cdc.connectors.values.sink.ValuesDataSinkOptions;
import org.apache.flink.cdc.connectors.values.source.ValuesDataSourceHelper;
import org.apache.flink.cdc.connectors.values.source.ValuesDataSourceOptions;
import org.apache.flink.cdc.runtime.typeutils.BinaryRecordDataGenerator;
import org.apache.flink.runtime.testutils.MiniClusterResourceConfiguration;
import org.apache.flink.test.junit5.MiniClusterExtension;

import org.apache.flink.shaded.guava31.com.google.common.collect.ImmutableMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.flink.configuration.CoreOptions.ALWAYS_PARENT_FIRST_LOADER_PATTERNS_ADDITIONAL;
import static org.assertj.core.api.Assertions.assertThat;

/** Integration test for {@link FlinkPipelineComposer}. */
class FlinkPipelineTransformITCase {

    private static final int MAX_PARALLELISM = 4;

    // Always use parent-first classloader for CDC classes.
    // The reason is that ValuesDatabase uses static field for holding data, we need to make sure
    // the class is loaded by AppClassloader so that we can verify data in the test case.
    private static final org.apache.flink.configuration.Configuration MINI_CLUSTER_CONFIG =
            new org.apache.flink.configuration.Configuration();

    static {
        MINI_CLUSTER_CONFIG.set(
                ALWAYS_PARENT_FIRST_LOADER_PATTERNS_ADDITIONAL,
                Collections.singletonList("org.apache.flink.cdc"));
    }

    /**
     * Use {@link MiniClusterExtension} to reduce the overhead of restarting the MiniCluster for
     * every test case.
     */
    @RegisterExtension
    static final MiniClusterExtension MINI_CLUSTER_RESOURCE =
            new MiniClusterExtension(
                    new MiniClusterResourceConfiguration.Builder()
                            .setNumberTaskManagers(1)
                            .setNumberSlotsPerTaskManager(MAX_PARALLELISM)
                            .setConfiguration(MINI_CLUSTER_CONFIG)
                            .build());

    private final PrintStream standardOut = System.out;
    private final ByteArrayOutputStream outCaptor = new ByteArrayOutputStream();

    @BeforeEach
    void init() {
        // Take over STDOUT as we need to check the output of values sink
        System.setOut(new PrintStream(outCaptor));
        // Initialize in-memory database
        ValuesDatabase.clear();
    }

    @AfterEach
    void cleanup() {
        System.setOut(standardOut);
    }

    /** This tests if we can append calculated columns based on existing columns. */
    @ParameterizedTest
    @EnumSource
    void testCalculatedColumns(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, id || name AS uid, age * 2 AS double_age",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`uid` STRING,`double_age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, 1Alice, 36], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, 2Bob, 40], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, 2Bob, 40], after=[2, Bob, 30, 2Bob, 60], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`uid` STRING,`double_age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, 3Carol, 30], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, 4Derrida, 50], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, 4Derrida, 50], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if we can reference a column more than once in projection expressions. */
    @ParameterizedTest
    @EnumSource
    void testMultipleReferencedColumnsInProjection(ValuesDataSink.SinkApi sinkApi)
            throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, CAST(age * age * age AS INT) AS cubic_age",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`cubic_age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, 5832], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, 8000], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, 8000], after=[2, Bob, 30, 27000], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`cubic_age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, 3375], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, 15625], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, 15625], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if we can reference a column more than once in filtering expressions. */
    @ParameterizedTest
    @EnumSource
    void testMultipleReferencedColumnsInFilter(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                null,
                                "id > 2 AND id < 4",
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT}, primaryKeys=id, options=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student], op=INSERT, meta=()}"));
    }

    /** This tests if we can filter out source records by rule. */
    @ParameterizedTest
    @EnumSource
    void testFilteringRules(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                null,
                                "CHAR_LENGTH(name) > 3",
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18], op=INSERT, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student], after=[], op=DELETE, meta=()}"));
    }

    /**
     * This tests if transform rule could be used to classify source records based on filtering
     * rules.
     */
    @ParameterizedTest
    @EnumSource
    void testMultipleDispatchTransform(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Arrays.asList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, 'YOUNG' AS category",
                                "age < 20",
                                null,
                                null,
                                null,
                                null),
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, 'OLD' AS category",
                                "age >= 20",
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`category` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, YOUNG], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, OLD], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, OLD], after=[2, Bob, 30, OLD], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`category` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, YOUNG], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, OLD], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, OLD], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if transform generates metadata info correctly. */
    @ParameterizedTest
    @EnumSource
    void testMetadataInfo(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*",
                                null,
                                "id,name",
                                "id",
                                "replication_num=1,bucket=17",
                                "Just a Transform Block")),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT}, primaryKeys=id;name, partitionKeys=id, options=({bucket=17, replication_num=1})}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20], after=[2, Bob, 30], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING}, primaryKeys=id;name, partitionKeys=id, options=({bucket=17, replication_num=1})}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student], after=[], op=DELETE, meta=()}"));
    }

    /**
     * This tests if transform generates metadata info correctly without specifying projection /
     * filtering rules.
     */
    @ParameterizedTest
    @EnumSource
    void testMetadataInfoWithoutChangingSchema(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                null,
                                null,
                                "id,name",
                                "id",
                                "replication_num=1,bucket=17",
                                "A Transform Block without projection or filter")),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT}, primaryKeys=id;name, partitionKeys=id, options=({bucket=17, replication_num=1})}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20], after=[2, Bob, 30], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING}, primaryKeys=id;name, partitionKeys=id, options=({bucket=17, replication_num=1})}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if projection rule could reference metadata info correctly. */
    @ParameterizedTest
    @EnumSource
    void testMetadataColumn(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "id, name, age, __namespace_name__, __schema_name__, __table_name__",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`__namespace_name__` STRING NOT NULL,`__schema_name__` STRING NOT NULL,`__table_name__` STRING NOT NULL}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, default_namespace, default_schema, mytable1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, default_namespace, default_schema, mytable1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, default_namespace, default_schema, mytable1], after=[2, Bob, 30, default_namespace, default_schema, mytable1], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`__namespace_name__` STRING NOT NULL,`__schema_name__` STRING NOT NULL,`__table_name__` STRING NOT NULL}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, default_namespace, default_schema, mytable2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, default_namespace, default_schema, mytable2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, default_namespace, default_schema, mytable2], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if projection rule could reference metadata info correctly with wildcard (*). */
    @ParameterizedTest
    @EnumSource
    void testMetadataColumnWithWildcard(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, __namespace_name__, __schema_name__, __table_name__",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`__namespace_name__` STRING NOT NULL,`__schema_name__` STRING NOT NULL,`__table_name__` STRING NOT NULL}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, default_namespace, default_schema, mytable1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, default_namespace, default_schema, mytable1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, default_namespace, default_schema, mytable1], after=[2, Bob, 30, default_namespace, default_schema, mytable1], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`__namespace_name__` STRING NOT NULL,`__schema_name__` STRING NOT NULL,`__table_name__` STRING NOT NULL}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, default_namespace, default_schema, mytable2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, default_namespace, default_schema, mytable2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, default_namespace, default_schema, mytable2], after=[], op=DELETE, meta=()}"));
    }

    /**
     * This tests if transform operator could distinguish metadata column identifiers and string
     * literals.
     */
    @ParameterizedTest
    @EnumSource
    void testUsingMetadataColumnLiteralWithWildcard(ValuesDataSink.SinkApi sinkApi)
            throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, '__namespace_name____schema_name____table_name__' AS string_literal",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`string_literal` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, __namespace_name____schema_name____table_name__], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, __namespace_name____schema_name____table_name__], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, __namespace_name____schema_name____table_name__], after=[2, Bob, 30, __namespace_name____schema_name____table_name__], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`string_literal` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, __namespace_name____schema_name____table_name__], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, __namespace_name____schema_name____table_name__], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, __namespace_name____schema_name____table_name__], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if built-in comparison functions work as expected. */
    @ParameterizedTest
    @EnumSource
    void testBuiltinComparisonFunctions(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, "
                                        + "id = 2 AS col1, id <> 3 AS col2, id > 2 as col3, "
                                        + "id >= 2 as col4, id < 3 as col5, id <= 4 as col6, "
                                        + "name IS NULL as col7, name IS NOT NULL as col8, "
                                        + "id BETWEEN 1 AND 3 as col9, id NOT BETWEEN 2 AND 4 as col10, "
                                        + "name LIKE 'li' as col11, name LIKE 'ro' as col12, "
                                        + "CAST(id AS INT) IN (1, 3, 5) as col13, name IN ('Bob', 'Derrida') AS col14",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`col1` BOOLEAN,`col2` BOOLEAN,`col3` BOOLEAN,`col4` BOOLEAN,`col5` BOOLEAN,`col6` BOOLEAN,`col7` BOOLEAN,`col8` BOOLEAN,`col9` BOOLEAN,`col10` BOOLEAN,`col11` BOOLEAN,`col12` BOOLEAN,`col13` BOOLEAN,`col14` BOOLEAN}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, false, true, false, false, true, true, false, true, true, true, true, false, true, false], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, true, true, false, true, true, true, false, true, true, false, false, false, false, true], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, true, true, false, true, true, true, false, true, true, false, false, false, false, true], after=[2, Bob, 30, true, true, false, true, true, true, false, true, true, false, false, false, false, true], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`col1` BOOLEAN,`col2` BOOLEAN,`col3` BOOLEAN,`col4` BOOLEAN,`col5` BOOLEAN,`col6` BOOLEAN,`col7` BOOLEAN,`col8` BOOLEAN,`col9` BOOLEAN,`col10` BOOLEAN,`col11` BOOLEAN,`col12` BOOLEAN,`col13` BOOLEAN,`col14` BOOLEAN}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, false, true, true, true, false, true, false, true, true, false, false, true, true, false], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, false, true, true, true, false, true, false, true, false, false, false, false, false, true], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, false, true, true, true, false, true, false, true, false, false, false, false, false, true], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if built-in logical functions work as expected. */
    @ParameterizedTest
    @EnumSource
    void testBuiltinLogicalFunctions(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, "
                                        + "id = 2 OR true as col1, id <> 3 OR false as col2, "
                                        + "name = 'Alice' AND true as col4, name <> 'Bob' AND false as col5, "
                                        + "NOT id = 1 as col6, id = 3 IS FALSE as col7, "
                                        + "name = 'Derrida' IS TRUE as col8, "
                                        + "name <> 'Carol' IS NOT FALSE as col9, "
                                        + "name <> 'Eve' IS NOT TRUE as col10",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`col1` BOOLEAN,`col2` BOOLEAN,`col4` BOOLEAN,`col5` BOOLEAN,`col6` BOOLEAN,`col7` BOOLEAN,`col8` BOOLEAN,`col9` BOOLEAN,`col10` BOOLEAN}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, true, true, true, false, false, true, false, true, false], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, true, true, false, false, true, true, false, true, false], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, true, true, false, false, true, true, false, true, false], after=[2, Bob, 30, true, true, false, false, true, true, false, true, false], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`col1` BOOLEAN,`col2` BOOLEAN,`col4` BOOLEAN,`col5` BOOLEAN,`col6` BOOLEAN,`col7` BOOLEAN,`col8` BOOLEAN,`col9` BOOLEAN,`col10` BOOLEAN}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, true, true, false, false, true, true, false, false, false], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, true, true, false, false, true, true, true, true, false], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, true, true, false, false, true, true, true, true, false], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if built-in arithmetic functions work as expected. */
    @ParameterizedTest
    @EnumSource
    void testBuiltinArithmeticFunctions(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, "
                                        + "id + 17 AS col1, id - 17 AS col2, id * 17 AS col3, "
                                        + "CAST(id AS DOUBLE) / 1.7 AS col4, "
                                        + "CAST(id AS INT) % 3 AS col5, ABS(id - 17) AS col6, "
                                        + "CEIL(CAST(id AS DOUBLE) / 1.7) AS col7, "
                                        + "FLOOR(CAST(id AS DOUBLE) / 1.7) AS col8, "
                                        + "ROUND(CAST(id AS DOUBLE) / 1.7) AS col9, "
                                        + "CHAR_LENGTH(UUID()) AS col10",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`col1` INT,`col2` INT,`col3` INT,`col4` DOUBLE,`col5` INT,`col6` INT,`col7` DOUBLE,`col8` DOUBLE,`col9` DOUBLE,`col10` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, 18, -16, 17, 0.5882352941176471, 1, 16, 1.0, 0.0, 1.0, 36], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, 19, -15, 34, 1.1764705882352942, 2, 15, 2.0, 1.0, 1.0, 36], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, 19, -15, 34, 1.1764705882352942, 2, 15, 2.0, 1.0, 1.0, 36], after=[2, Bob, 30, 19, -15, 34, 1.1764705882352942, 2, 15, 2.0, 1.0, 1.0, 36], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`col1` BIGINT,`col2` BIGINT,`col3` BIGINT,`col4` DOUBLE,`col5` INT,`col6` BIGINT,`col7` DOUBLE,`col8` DOUBLE,`col9` DOUBLE,`col10` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, 20, -14, 51, 1.7647058823529411, 0, 14, 2.0, 1.0, 2.0, 36], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, 21, -13, 68, 2.3529411764705883, 1, 13, 3.0, 2.0, 2.0, 36], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, 21, -13, 68, 2.3529411764705883, 1, 13, 3.0, 2.0, 2.0, 36], after=[], op=DELETE, meta=()}"));
    }

    /** This tests if built-in string functions work as expected. */
    @ParameterizedTest
    @EnumSource
    void testBuiltinStringFunctions(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, "
                                        + "'Dear ' || name AS col1, "
                                        + "CHAR_LENGTH(name) AS col2, "
                                        + "UPPER(name) AS col3, "
                                        + "LOWER(name) AS col4, "
                                        + "TRIM(name) AS col5, "
                                        + "REGEXP_REPLACE(name, 'Al|Bo', '**') AS col6, "
                                        + "SUBSTR(name, 1, 1) AS col7, "
                                        + "SUBSTR(name, 2, 1) AS col8, "
                                        + "SUBSTR(name, 3) AS col9, "
                                        + "CONCAT(name, ' - ', CAST(id AS VARCHAR)) AS col10",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`col1` STRING,`col2` INT,`col3` STRING,`col4` STRING,`col5` STRING,`col6` STRING,`col7` STRING,`col8` STRING,`col9` STRING,`col10` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 18, Dear Alice, 5, ALICE, alice, Alice, **ice, A, l, ice, Alice - 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Bob, 20, Dear Bob, 3, BOB, bob, Bob, **b, B, o, b, Bob - 2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Bob, 20, Dear Bob, 3, BOB, bob, Bob, **b, B, o, b, Bob - 2], after=[2, Bob, 30, Dear Bob, 3, BOB, bob, Bob, **b, B, o, b, Bob - 2], op=UPDATE, meta=()}",
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable2, schema=columns={`id` BIGINT,`name` VARCHAR(255),`age` TINYINT,`description` STRING,`col1` STRING,`col2` INT,`col3` STRING,`col4` STRING,`col5` STRING,`col6` STRING,`col7` STRING,`col8` STRING,`col9` STRING,`col10` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[3, Carol, 15, student, Dear Carol, 5, CAROL, carol, Carol, Carol, C, a, rol, Carol - 3], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[], after=[4, Derrida, 25, student, Dear Derrida, 7, DERRIDA, derrida, Derrida, Derrida, D, e, rrida, Derrida - 4], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable2, before=[4, Derrida, 25, student, Dear Derrida, 7, DERRIDA, derrida, Derrida, Derrida, D, e, rrida, Derrida - 4], after=[], op=DELETE, meta=()}"));
    }

    @ParameterizedTest
    @EnumSource
    @Disabled("SUBSTRING ... FROM ... FOR ... isn't available until we close FLINK-35985.")
    void testSubstringFunctions(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, "
                                        + "SUBSTR(name, 0, 1) AS col1, "
                                        + "SUBSTR(name, 2, 1) AS col2, "
                                        + "SUBSTR(name, 3) AS col3, "
                                        + "SUBSTRING(name FROM 0 FOR 1) AS col4, "
                                        + "SUBSTRING(name FROM 2 FOR 1) AS col5, "
                                        + "SUBSTRING(name FROM 3) AS col6",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList("To", "be", "added"));
    }

    /** This tests if built-in conditional functions work as expected. */
    @ParameterizedTest
    @EnumSource
    @Disabled("This case will not run until we close FLINK-35986.")
    void testConditionalFunctions(ValuesDataSink.SinkApi sinkApi) throws Exception {
        runGenericTransformTest(
                sinkApi,
                Collections.singletonList(
                        new TransformDef(
                                "default_namespace.default_schema.\\.*",
                                "*, "
                                        + "CASE UPPER(name)"
                                        + "  WHEN 'ALICE' THEN 'A - Alice'"
                                        + "  WHEN 'BOB' THEN 'B - Bob'"
                                        + "  WHEN 'CAROL' THEN 'C - Carol'"
                                        + "  ELSE 'D - Derrida' END AS col1, "
                                        + "CASE"
                                        + "  WHEN id = 1 THEN '1 - One'"
                                        + "  WHEN id = 2 THEN '2 - Two'"
                                        + "  WHEN id = 3 THEN '3 - Three'"
                                        + "  ELSE '4 - Four' END AS col2, "
                                        + "COALESCE(name, 'FALLBACK') AS col3, "
                                        + "COALESCE(NULL, NULL, id, 42, NULL) AS col4, "
                                        + "COALESCE(NULL, NULL, NULL, NULL, NULL) AS col5, "
                                        + "IF(TRUE, 'true', 'false') AS col6, "
                                        + "IF(id < 3, 'ID < 3', 'ID >= 3') AS col7, "
                                        + "IF(name = 'Alice', IF(id = 1, 'YES', 'NO'), 'NO') AS col8",
                                null,
                                null,
                                null,
                                null,
                                null)),
                Arrays.asList("Foo", "Bar", "Baz"));
    }

    /** This tests if transform temporal functions works as expected. */
    @Test
    void testTransformWithTemporalFunction() throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId myTable1 = TableId.tableId("default_namespace", "default_schema", "mytable1");
        TableId myTable2 = TableId.tableId("default_namespace", "default_schema", "mytable2");
        Schema table1Schema =
                Schema.newBuilder()
                        .physicalColumn("id", DataTypes.INT())
                        .physicalColumn("name", DataTypes.STRING())
                        .physicalColumn("age", DataTypes.INT())
                        .primaryKey("id")
                        .build();
        Schema table2Schema =
                Schema.newBuilder()
                        .physicalColumn("id", DataTypes.BIGINT())
                        .physicalColumn("name", DataTypes.VARCHAR(255))
                        .physicalColumn("age", DataTypes.TINYINT())
                        .physicalColumn("description", DataTypes.STRING())
                        .primaryKey("id")
                        .build();

        List<Event> events = getTestEvents(table1Schema, table2Schema, myTable1, myTable2);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        sinkConfig.set(ValuesDataSinkOptions.MATERIALIZED_IN_MEMORY, true);
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        pipelineConfig.set(PipelineOptions.PIPELINE_LOCAL_TIME_ZONE, "America/Los_Angeles");
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        Collections.singletonList(
                                new TransformDef(
                                        "default_namespace.default_schema.\\.*",
                                        "*, LOCALTIME as lcl_t, CURRENT_TIME as cur_t, CAST(CURRENT_TIMESTAMP AS TIMESTAMP) as cur_ts, CAST(NOW() AS TIMESTAMP) as now_ts, LOCALTIMESTAMP as lcl_ts, CURRENT_DATE as cur_dt",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)),
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        Arrays.stream(outputEvents).forEach(this::extractDataLines);
    }

    void runGenericTransformTest(
            ValuesDataSink.SinkApi sinkApi,
            List<TransformDef> transformDefs,
            List<String> expectedResults)
            throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId myTable1 = TableId.tableId("default_namespace", "default_schema", "mytable1");
        TableId myTable2 = TableId.tableId("default_namespace", "default_schema", "mytable2");
        Schema table1Schema =
                Schema.newBuilder()
                        .physicalColumn("id", DataTypes.INT())
                        .physicalColumn("name", DataTypes.STRING())
                        .physicalColumn("age", DataTypes.INT())
                        .primaryKey("id")
                        .build();
        Schema table2Schema =
                Schema.newBuilder()
                        .physicalColumn("id", DataTypes.BIGINT())
                        .physicalColumn("name", DataTypes.VARCHAR(255))
                        .physicalColumn("age", DataTypes.TINYINT())
                        .physicalColumn("description", DataTypes.STRING())
                        .primaryKey("id")
                        .build();

        List<Event> events = getTestEvents(table1Schema, table2Schema, myTable1, myTable2);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        sinkConfig.set(ValuesDataSinkOptions.MATERIALIZED_IN_MEMORY, true);
        sinkConfig.set(ValuesDataSinkOptions.SINK_API, sinkApi);
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        transformDefs,
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        assertThat(outputEvents).containsExactly(expectedResults.toArray(new String[0]));
    }

    private static List<Event> getTestEvents(
            Schema table1Schema, Schema table2Schema, TableId myTable1, TableId myTable2) {
        List<Event> events = new ArrayList<>();
        BinaryRecordDataGenerator table1dataGenerator =
                new BinaryRecordDataGenerator(
                        table1Schema.getColumnDataTypes().toArray(new DataType[0]));
        BinaryRecordDataGenerator table2dataGenerator =
                new BinaryRecordDataGenerator(
                        table2Schema.getColumnDataTypes().toArray(new DataType[0]));
        events.add(new CreateTableEvent(myTable1, table1Schema));
        events.add(
                DataChangeEvent.insertEvent(
                        myTable1,
                        table1dataGenerator.generate(
                                new Object[] {1, BinaryStringData.fromString("Alice"), 18})));
        events.add(
                DataChangeEvent.insertEvent(
                        myTable1,
                        table1dataGenerator.generate(
                                new Object[] {2, BinaryStringData.fromString("Bob"), 20})));
        events.add(
                DataChangeEvent.updateEvent(
                        myTable1,
                        table1dataGenerator.generate(
                                new Object[] {2, BinaryStringData.fromString("Bob"), 20}),
                        table1dataGenerator.generate(
                                new Object[] {2, BinaryStringData.fromString("Bob"), 30})));
        events.add(new CreateTableEvent(myTable2, table2Schema));
        events.add(
                DataChangeEvent.insertEvent(
                        myTable2,
                        table2dataGenerator.generate(
                                new Object[] {
                                    3L,
                                    BinaryStringData.fromString("Carol"),
                                    (byte) 15,
                                    BinaryStringData.fromString("student")
                                })));
        events.add(
                DataChangeEvent.insertEvent(
                        myTable2,
                        table2dataGenerator.generate(
                                new Object[] {
                                    4L,
                                    BinaryStringData.fromString("Derrida"),
                                    (byte) 25,
                                    BinaryStringData.fromString("student")
                                })));
        events.add(
                DataChangeEvent.deleteEvent(
                        myTable2,
                        table2dataGenerator.generate(
                                new Object[] {
                                    4L,
                                    BinaryStringData.fromString("Derrida"),
                                    (byte) 25,
                                    BinaryStringData.fromString("student")
                                })));
        return events;
    }

    @Test
    void testVanillaTransformWithSchemaEvolution() throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId tableId = TableId.tableId("default_namespace", "default_schema", "mytable1");
        List<Event> events = generateSchemaEvolutionEvents(tableId);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        pipelineConfig.set(
                PipelineOptions.PIPELINE_SCHEMA_CHANGE_BEHAVIOR, SchemaChangeBehavior.EVOLVE);
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        assertThat(outputEvents)
                .containsExactly(
                        // Initial stage
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 21], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Barcarolle, 22], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3, Cecily, 23], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3, Cecily, 23], after=[3, Colin, 24], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Barcarolle, 22], after=[], op=DELETE, meta=()}",

                        // Add column stage
                        "AddColumnEvent{tableId=default_namespace.default_schema.mytable1, addedColumns=[ColumnWithPosition{column=`rank` STRING, position=FIRST, existedColumnName=null}, ColumnWithPosition{column=`gender` TINYINT, position=LAST, existedColumnName=null}]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1st, 4, Derrida, 24, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2nd, 5, Eve, 25, 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2nd, 5, Eve, 25, 1], after=[2nd, 5, Eva, 20, 2], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3rd, 6, Fiona, 26, 3], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3rd, 6, Fiona, 26, 3], after=[], op=DELETE, meta=()}",

                        // Alter column type stage
                        "AlterColumnTypeEvent{tableId=default_namespace.default_schema.mytable1, typeMapping={gender=INT, name=VARCHAR(17), age=DOUBLE}, oldTypeMapping={gender=TINYINT, name=STRING, age=INT}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[4th, 7, Gem, 19.0, -1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[5th, 8, Helen, 18.0, -2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[5th, 8, Helen, 18.0, -2], after=[5th, 8, Harry, 18.0, -3], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[6th, 9, IINA, 17.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[6th, 9, IINA, 17.0, 0], after=[], op=DELETE, meta=()}",

                        // Rename column stage
                        "RenameColumnEvent{tableId=default_namespace.default_schema.mytable1, nameMapping={gender=biological_sex, age=toshi}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[7th, 10, Julia, 24.0, 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[8th, 11, Kalle, 23.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[8th, 11, Kalle, 23.0, 0], after=[8th, 11, Kella, 18.0, 0], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[9th, 12, Lynx, 17.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[9th, 12, Lynx, 17.0, 0], after=[], op=DELETE, meta=()}",

                        // Drop column stage
                        "DropColumnEvent{tableId=default_namespace.default_schema.mytable1, droppedColumnNames=[biological_sex, toshi]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[10th, 13, Munroe], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[11th, 14, Neko], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[11th, 14, Neko], after=[11th, 14, Nein], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[12th, 15, Oops], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[12th, 15, Oops], after=[], op=DELETE, meta=()}");
    }

    @Test
    void testWildcardTransformWithSchemaEvolution() throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId tableId = TableId.tableId("default_namespace", "default_schema", "mytable1");
        List<Event> events = generateSchemaEvolutionEvents(tableId);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        pipelineConfig.set(
                PipelineOptions.PIPELINE_SCHEMA_CHANGE_BEHAVIOR, SchemaChangeBehavior.EVOLVE);
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        Collections.singletonList(
                                new TransformDef(
                                        tableId.toString(), "*", null, null, null, null, null)),
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        assertThat(outputEvents)
                .containsExactly(
                        // Initial stage
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 21], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Barcarolle, 22], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3, Cecily, 23], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3, Cecily, 23], after=[3, Colin, 24], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Barcarolle, 22], after=[], op=DELETE, meta=()}",

                        // Add column stage
                        "AddColumnEvent{tableId=default_namespace.default_schema.mytable1, addedColumns=[ColumnWithPosition{column=`rank` STRING, position=BEFORE, existedColumnName=id}, ColumnWithPosition{column=`gender` TINYINT, position=AFTER, existedColumnName=age}]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1st, 4, Derrida, 24, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2nd, 5, Eve, 25, 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2nd, 5, Eve, 25, 1], after=[2nd, 5, Eva, 20, 2], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3rd, 6, Fiona, 26, 3], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3rd, 6, Fiona, 26, 3], after=[], op=DELETE, meta=()}",

                        // Alter column type stage
                        "AlterColumnTypeEvent{tableId=default_namespace.default_schema.mytable1, typeMapping={gender=INT, name=VARCHAR(17), age=DOUBLE}, oldTypeMapping={gender=TINYINT, name=STRING, age=INT}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[4th, 7, Gem, 19.0, -1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[5th, 8, Helen, 18.0, -2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[5th, 8, Helen, 18.0, -2], after=[5th, 8, Harry, 18.0, -3], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[6th, 9, IINA, 17.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[6th, 9, IINA, 17.0, 0], after=[], op=DELETE, meta=()}",

                        // Rename column stage
                        "RenameColumnEvent{tableId=default_namespace.default_schema.mytable1, nameMapping={gender=biological_sex, age=toshi}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[7th, 10, Julia, 24.0, 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[8th, 11, Kalle, 23.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[8th, 11, Kalle, 23.0, 0], after=[8th, 11, Kella, 18.0, 0], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[9th, 12, Lynx, 17.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[9th, 12, Lynx, 17.0, 0], after=[], op=DELETE, meta=()}",

                        // Drop column stage
                        "DropColumnEvent{tableId=default_namespace.default_schema.mytable1, droppedColumnNames=[biological_sex, toshi]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[10th, 13, Munroe], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[11th, 14, Neko], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[11th, 14, Neko], after=[11th, 14, Nein], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[12th, 15, Oops], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[12th, 15, Oops], after=[], op=DELETE, meta=()}");
    }

    @Test
    void testExplicitTransformWithSchemaEvolution() throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId tableId = TableId.tableId("default_namespace", "default_schema", "mytable1");
        List<Event> events = generateSchemaEvolutionEvents(tableId);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        pipelineConfig.set(
                PipelineOptions.PIPELINE_SCHEMA_CHANGE_BEHAVIOR, SchemaChangeBehavior.EVOLVE);
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        Collections.singletonList(
                                new TransformDef(
                                        tableId.toString(),
                                        "id, name, CAST(id AS VARCHAR) || ' -> ' || name AS extend_id",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)),
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        assertThat(outputEvents)
                .containsExactly(
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`extend_id` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 1 -> Alice], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Barcarolle, 2 -> Barcarolle], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3, Cecily, 3 -> Cecily], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3, Cecily, 3 -> Cecily], after=[3, Colin, 3 -> Colin], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Barcarolle, 2 -> Barcarolle], after=[], op=DELETE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[4, Derrida, 4 -> Derrida], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[5, Eve, 5 -> Eve], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[5, Eve, 5 -> Eve], after=[5, Eva, 5 -> Eva], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[6, Fiona, 6 -> Fiona], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[6, Fiona, 6 -> Fiona], after=[], op=DELETE, meta=()}",
                        "AlterColumnTypeEvent{tableId=default_namespace.default_schema.mytable1, typeMapping={name=VARCHAR(17)}, oldTypeMapping={name=STRING}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[7, Gem, 7 -> Gem], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[8, Helen, 8 -> Helen], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[8, Helen, 8 -> Helen], after=[8, Harry, 8 -> Harry], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[9, IINA, 9 -> IINA], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[9, IINA, 9 -> IINA], after=[], op=DELETE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[10, Julia, 10 -> Julia], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[11, Kalle, 11 -> Kalle], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[11, Kalle, 11 -> Kalle], after=[11, Kella, 11 -> Kella], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[12, Lynx, 12 -> Lynx], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[12, Lynx, 12 -> Lynx], after=[], op=DELETE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[13, Munroe, 13 -> Munroe], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[14, Neko, 14 -> Neko], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[14, Neko, 14 -> Neko], after=[14, Nein, 14 -> Nein], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[15, Oops, 15 -> Oops], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[15, Oops, 15 -> Oops], after=[], op=DELETE, meta=()}");
    }

    @Test
    void testPreAsteriskWithSchemaEvolution() throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId tableId = TableId.tableId("default_namespace", "default_schema", "mytable1");
        List<Event> events = generateSchemaEvolutionEvents(tableId);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        pipelineConfig.set(
                PipelineOptions.PIPELINE_SCHEMA_CHANGE_BEHAVIOR, SchemaChangeBehavior.EVOLVE);
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        Collections.singletonList(
                                new TransformDef(
                                        tableId.toString(),
                                        "*, CAST(id AS VARCHAR) || ' -> ' || name AS extend_id",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null)),
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        assertThat(outputEvents)
                .containsExactly(
                        // Initial stage
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`id` INT,`name` STRING,`age` INT,`extend_id` STRING}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1, Alice, 21, 1 -> Alice], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2, Barcarolle, 22, 2 -> Barcarolle], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3, Cecily, 23, 3 -> Cecily], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3, Cecily, 23, 3 -> Cecily], after=[3, Colin, 24, 3 -> Colin], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2, Barcarolle, 22, 2 -> Barcarolle], after=[], op=DELETE, meta=()}",

                        // Add column stage
                        "AddColumnEvent{tableId=default_namespace.default_schema.mytable1, addedColumns=[ColumnWithPosition{column=`rank` STRING, position=BEFORE, existedColumnName=id}, ColumnWithPosition{column=`gender` TINYINT, position=AFTER, existedColumnName=age}]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1st, 4, Derrida, 24, 0, 4 -> Derrida], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2nd, 5, Eve, 25, 1, 5 -> Eve], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2nd, 5, Eve, 25, 1, 5 -> Eve], after=[2nd, 5, Eva, 20, 2, 5 -> Eva], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3rd, 6, Fiona, 26, 3, 6 -> Fiona], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3rd, 6, Fiona, 26, 3, 6 -> Fiona], after=[], op=DELETE, meta=()}",

                        // Alter column type stage
                        "AlterColumnTypeEvent{tableId=default_namespace.default_schema.mytable1, typeMapping={gender=INT, name=VARCHAR(17), age=DOUBLE}, oldTypeMapping={gender=TINYINT, name=STRING, age=INT}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[4th, 7, Gem, 19.0, -1, 7 -> Gem], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[5th, 8, Helen, 18.0, -2, 8 -> Helen], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[5th, 8, Helen, 18.0, -2, 8 -> Helen], after=[5th, 8, Harry, 18.0, -3, 8 -> Harry], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[6th, 9, IINA, 17.0, 0, 9 -> IINA], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[6th, 9, IINA, 17.0, 0, 9 -> IINA], after=[], op=DELETE, meta=()}",

                        // Rename column stage
                        "RenameColumnEvent{tableId=default_namespace.default_schema.mytable1, nameMapping={gender=biological_sex, age=toshi}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[7th, 10, Julia, 24.0, 1, 10 -> Julia], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[8th, 11, Kalle, 23.0, 0, 11 -> Kalle], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[8th, 11, Kalle, 23.0, 0, 11 -> Kalle], after=[8th, 11, Kella, 18.0, 0, 11 -> Kella], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[9th, 12, Lynx, 17.0, 0, 12 -> Lynx], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[9th, 12, Lynx, 17.0, 0, 12 -> Lynx], after=[], op=DELETE, meta=()}",

                        // Drop column stage
                        "DropColumnEvent{tableId=default_namespace.default_schema.mytable1, droppedColumnNames=[biological_sex, toshi]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[10th, 13, Munroe, 13 -> Munroe], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[11th, 14, Neko, 14 -> Neko], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[11th, 14, Neko, 14 -> Neko], after=[11th, 14, Nein, 14 -> Nein], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[12th, 15, Oops, 15 -> Oops], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[12th, 15, Oops, 15 -> Oops], after=[], op=DELETE, meta=()}");
    }

    @Test
    void testPostAsteriskWithSchemaEvolution() throws Exception {
        FlinkPipelineComposer composer = FlinkPipelineComposer.ofMiniCluster();

        // Setup value source
        Configuration sourceConfig = new Configuration();
        sourceConfig.set(
                ValuesDataSourceOptions.EVENT_SET_ID,
                ValuesDataSourceHelper.EventSetId.CUSTOM_SOURCE_EVENTS);

        TableId tableId = TableId.tableId("default_namespace", "default_schema", "mytable1");
        List<Event> events = generateSchemaEvolutionEvents(tableId);

        ValuesDataSourceHelper.setSourceEvents(Collections.singletonList(events));

        SourceDef sourceDef =
                new SourceDef(ValuesDataFactory.IDENTIFIER, "Value Source", sourceConfig);

        // Setup value sink
        Configuration sinkConfig = new Configuration();
        SinkDef sinkDef = new SinkDef(ValuesDataFactory.IDENTIFIER, "Value Sink", sinkConfig);

        // Setup pipeline
        Configuration pipelineConfig = new Configuration();
        pipelineConfig.set(PipelineOptions.PIPELINE_PARALLELISM, 1);
        pipelineConfig.set(
                PipelineOptions.PIPELINE_SCHEMA_CHANGE_BEHAVIOR, SchemaChangeBehavior.EVOLVE);
        PipelineDef pipelineDef =
                new PipelineDef(
                        sourceDef,
                        sinkDef,
                        Collections.emptyList(),
                        Collections.singletonList(
                                new TransformDef(
                                        tableId.toString(),
                                        "CAST(id AS VARCHAR) || ' -> ' || name AS extend_id, *",
                                        "id > 0",
                                        null,
                                        null,
                                        null,
                                        null)),
                        Collections.emptyList(),
                        pipelineConfig);

        // Execute the pipeline
        PipelineExecution execution = composer.compose(pipelineDef);
        execution.execute();

        // Check the order and content of all received events
        String[] outputEvents = outCaptor.toString().trim().split("\n");

        assertThat(outputEvents)
                .containsExactly(
                        // Initial stage
                        "CreateTableEvent{tableId=default_namespace.default_schema.mytable1, schema=columns={`extend_id` STRING,`id` INT,`name` STRING,`age` INT}, primaryKeys=id, options=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[1 -> Alice, 1, Alice, 21], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[2 -> Barcarolle, 2, Barcarolle, 22], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[3 -> Cecily, 3, Cecily, 23], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[3 -> Cecily, 3, Cecily, 23], after=[3 -> Colin, 3, Colin, 24], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[2 -> Barcarolle, 2, Barcarolle, 22], after=[], op=DELETE, meta=()}",

                        // Add column stage
                        "AddColumnEvent{tableId=default_namespace.default_schema.mytable1, addedColumns=[ColumnWithPosition{column=`rank` STRING, position=BEFORE, existedColumnName=id}, ColumnWithPosition{column=`gender` TINYINT, position=AFTER, existedColumnName=age}]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[4 -> Derrida, 1st, 4, Derrida, 24, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[5 -> Eve, 2nd, 5, Eve, 25, 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[5 -> Eve, 2nd, 5, Eve, 25, 1], after=[5 -> Eva, 2nd, 5, Eva, 20, 2], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[6 -> Fiona, 3rd, 6, Fiona, 26, 3], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[6 -> Fiona, 3rd, 6, Fiona, 26, 3], after=[], op=DELETE, meta=()}",

                        // Alter column type stage
                        "AlterColumnTypeEvent{tableId=default_namespace.default_schema.mytable1, typeMapping={gender=INT, name=VARCHAR(17), age=DOUBLE}, oldTypeMapping={gender=TINYINT, name=STRING, age=INT}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[7 -> Gem, 4th, 7, Gem, 19.0, -1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[8 -> Helen, 5th, 8, Helen, 18.0, -2], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[8 -> Helen, 5th, 8, Helen, 18.0, -2], after=[8 -> Harry, 5th, 8, Harry, 18.0, -3], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[9 -> IINA, 6th, 9, IINA, 17.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[9 -> IINA, 6th, 9, IINA, 17.0, 0], after=[], op=DELETE, meta=()}",

                        // Rename column stage
                        "RenameColumnEvent{tableId=default_namespace.default_schema.mytable1, nameMapping={gender=biological_sex, age=toshi}}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[10 -> Julia, 7th, 10, Julia, 24.0, 1], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[11 -> Kalle, 8th, 11, Kalle, 23.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[11 -> Kalle, 8th, 11, Kalle, 23.0, 0], after=[11 -> Kella, 8th, 11, Kella, 18.0, 0], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[12 -> Lynx, 9th, 12, Lynx, 17.0, 0], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[12 -> Lynx, 9th, 12, Lynx, 17.0, 0], after=[], op=DELETE, meta=()}",

                        // Drop column stage
                        "DropColumnEvent{tableId=default_namespace.default_schema.mytable1, droppedColumnNames=[biological_sex, toshi]}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[13 -> Munroe, 10th, 13, Munroe], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[14 -> Neko, 11th, 14, Neko], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[14 -> Neko, 11th, 14, Neko], after=[14 -> Nein, 11th, 14, Nein], op=UPDATE, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[], after=[15 -> Oops, 12th, 15, Oops], op=INSERT, meta=()}",
                        "DataChangeEvent{tableId=default_namespace.default_schema.mytable1, before=[15 -> Oops, 12th, 15, Oops], after=[], op=DELETE, meta=()}");
    }

    private List<Event> generateSchemaEvolutionEvents(TableId tableId) {
        List<Event> events = new ArrayList<>();

        // Initial schema
        {
            Schema schemaV1 =
                    Schema.newBuilder()
                            .physicalColumn("id", DataTypes.INT())
                            .physicalColumn("name", DataTypes.STRING())
                            .physicalColumn("age", DataTypes.INT())
                            .primaryKey("id")
                            .build();

            events.add(new CreateTableEvent(tableId, schemaV1));
            events.add(DataChangeEvent.insertEvent(tableId, generate(schemaV1, 1, "Alice", 21)));
            events.add(
                    DataChangeEvent.insertEvent(tableId, generate(schemaV1, 2, "Barcarolle", 22)));
            events.add(DataChangeEvent.insertEvent(tableId, generate(schemaV1, 3, "Cecily", 23)));
            events.add(
                    DataChangeEvent.updateEvent(
                            tableId,
                            generate(schemaV1, 3, "Cecily", 23),
                            generate(schemaV1, 3, "Colin", 24)));
            events.add(
                    DataChangeEvent.deleteEvent(tableId, generate(schemaV1, 2, "Barcarolle", 22)));
        }

        // test AddColumnEvent
        {
            events.add(
                    new AddColumnEvent(
                            tableId,
                            Arrays.asList(
                                    new AddColumnEvent.ColumnWithPosition(
                                            Column.physicalColumn("rank", DataTypes.STRING()),
                                            AddColumnEvent.ColumnPosition.FIRST,
                                            null),
                                    new AddColumnEvent.ColumnWithPosition(
                                            Column.physicalColumn(
                                                    "gender", DataTypes.TINYINT())))));
            Schema schemaV2 =
                    Schema.newBuilder()
                            .physicalColumn("rank", DataTypes.STRING())
                            .physicalColumn("id", DataTypes.INT())
                            .physicalColumn("name", DataTypes.STRING())
                            .physicalColumn("age", DataTypes.INT())
                            .physicalColumn("gender", DataTypes.TINYINT())
                            .primaryKey("id")
                            .build();
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV2, "1st", 4, "Derrida", 24, (byte) 0)));
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV2, "2nd", 5, "Eve", 25, (byte) 1)));
            events.add(
                    DataChangeEvent.updateEvent(
                            tableId,
                            generate(schemaV2, "2nd", 5, "Eve", 25, (byte) 1),
                            generate(schemaV2, "2nd", 5, "Eva", 20, (byte) 2)));
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV2, "3rd", 6, "Fiona", 26, (byte) 3)));
            events.add(
                    DataChangeEvent.deleteEvent(
                            tableId, generate(schemaV2, "3rd", 6, "Fiona", 26, (byte) 3)));
        }

        // test AlterColumnTypeEvent
        {
            events.add(
                    new AlterColumnTypeEvent(
                            tableId,
                            ImmutableMap.of(
                                    "age",
                                    DataTypes.DOUBLE(),
                                    "gender",
                                    DataTypes.INT(),
                                    "name",
                                    DataTypes.VARCHAR(17))));
            Schema schemaV3 =
                    Schema.newBuilder()
                            .physicalColumn("rank", DataTypes.STRING())
                            .physicalColumn("id", DataTypes.INT())
                            .physicalColumn("name", DataTypes.STRING())
                            .physicalColumn("age", DataTypes.DOUBLE())
                            .physicalColumn("gender", DataTypes.INT())
                            .primaryKey("id")
                            .build();
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV3, "4th", 7, "Gem", 19d, -1)));
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV3, "5th", 8, "Helen", 18d, -2)));
            events.add(
                    DataChangeEvent.updateEvent(
                            tableId,
                            generate(schemaV3, "5th", 8, "Helen", 18d, -2),
                            generate(schemaV3, "5th", 8, "Harry", 18d, -3)));
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV3, "6th", 9, "IINA", 17d, 0)));
            events.add(
                    DataChangeEvent.deleteEvent(
                            tableId, generate(schemaV3, "6th", 9, "IINA", 17d, 0)));
        }

        // test RenameColumnEvent
        {
            events.add(
                    new RenameColumnEvent(
                            tableId, ImmutableMap.of("gender", "biological_sex", "age", "toshi")));
            Schema schemaV4 =
                    Schema.newBuilder()
                            .physicalColumn("rank", DataTypes.STRING())
                            .physicalColumn("id", DataTypes.INT())
                            .physicalColumn("name", DataTypes.STRING())
                            .physicalColumn("toshi", DataTypes.DOUBLE())
                            .physicalColumn("biological_sex", DataTypes.INT())
                            .primaryKey("id")
                            .build();
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV4, "7th", 10, "Julia", 24d, 1)));
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV4, "8th", 11, "Kalle", 23d, 0)));
            events.add(
                    DataChangeEvent.updateEvent(
                            tableId,
                            generate(schemaV4, "8th", 11, "Kalle", 23d, 0),
                            generate(schemaV4, "8th", 11, "Kella", 18d, 0)));
            events.add(
                    DataChangeEvent.insertEvent(
                            tableId, generate(schemaV4, "9th", 12, "Lynx", 17d, 0)));
            events.add(
                    DataChangeEvent.deleteEvent(
                            tableId, generate(schemaV4, "9th", 12, "Lynx", 17d, 0)));
        }

        // test DropColumnEvent
        {
            events.add(new DropColumnEvent(tableId, Arrays.asList("biological_sex", "toshi")));
            Schema schemaV5 =
                    Schema.newBuilder()
                            .physicalColumn("rank", DataTypes.STRING())
                            .physicalColumn("id", DataTypes.INT())
                            .physicalColumn("name", DataTypes.STRING())
                            .primaryKey("id")
                            .build();
            events.add(
                    DataChangeEvent.insertEvent(tableId, generate(schemaV5, "10th", 13, "Munroe")));
            events.add(
                    DataChangeEvent.insertEvent(tableId, generate(schemaV5, "11th", 14, "Neko")));
            events.add(
                    DataChangeEvent.updateEvent(
                            tableId,
                            generate(schemaV5, "11th", 14, "Neko"),
                            generate(schemaV5, "11th", 14, "Nein")));
            events.add(
                    DataChangeEvent.insertEvent(tableId, generate(schemaV5, "12th", 15, "Oops")));
            events.add(
                    DataChangeEvent.deleteEvent(tableId, generate(schemaV5, "12th", 15, "Oops")));
        }
        return events;
    }

    void extractDataLines(String line) {
        if (!line.startsWith("DataChangeEvent{")) {
            return;
        }
        Stream.of("before", "after")
                .forEach(
                        tag -> {
                            String[] arr = line.split(tag + "=\\[", 2);
                            String dataRecord = arr[arr.length - 1].split("]", 2)[0];
                            if (!dataRecord.isEmpty()) {
                                verifyDataRecord(dataRecord);
                            }
                        });
    }

    void verifyDataRecord(String recordLine) {
        List<String> tokens = Arrays.asList(recordLine.split(", "));
        assertThat(tokens).hasSizeGreaterThanOrEqualTo(6);

        tokens = tokens.subList(tokens.size() - 6, tokens.size());

        String localTime = tokens.get(0);
        String currentTime = tokens.get(1);
        assertThat(localTime).isEqualTo(currentTime);

        String currentTimestamp = tokens.get(2);
        String nowTimestamp = tokens.get(3);
        String localTimestamp = tokens.get(4);
        assertThat(currentTimestamp).isEqualTo(nowTimestamp).isEqualTo(localTimestamp);

        // If timestamp millisecond part is .000, it will be truncated to yyyy-MM-dd'T'HH:mm:ss
        // format. Manually append this for the following checks.
        if (currentTimestamp.length() == 19) {
            currentTimestamp += ".000";
        }

        Instant instant =
                LocalDateTime.parse(
                                currentTimestamp,
                                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS"))
                        .toInstant(ZoneOffset.UTC);

        long milliSecondsInOneDay = 24 * 60 * 60 * 1000;

        assertThat(instant.toEpochMilli() % milliSecondsInOneDay)
                .isEqualTo(Long.parseLong(localTime));

        String currentDate = tokens.get(5);

        assertThat(instant.toEpochMilli() / milliSecondsInOneDay)
                .isEqualTo(Long.parseLong(currentDate));
    }

    BinaryRecordData generate(Schema schema, Object... fields) {
        return (new BinaryRecordDataGenerator(schema.getColumnDataTypes().toArray(new DataType[0])))
                .generate(
                        Arrays.stream(fields)
                                .map(
                                        e ->
                                                (e instanceof String)
                                                        ? BinaryStringData.fromString((String) e)
                                                        : e)
                                .toArray());
    }
}
