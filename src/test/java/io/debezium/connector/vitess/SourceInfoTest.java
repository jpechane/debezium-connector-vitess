/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.vitess;

import static org.fest.assertions.Assertions.assertThat;

import java.time.Instant;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.Before;
import org.junit.Test;

import io.debezium.config.Configuration;
import io.debezium.connector.AbstractSourceInfoStructMaker;
import io.debezium.connector.SnapshotRecord;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;

public class SourceInfoTest {

    private SourceInfo source;

    @Before
    public void beforeEach() {
        final VitessConnectorConfig connectorConfig = new VitessConnectorConfig(
                Configuration.create()
                        .with(RelationalDatabaseConnectorConfig.SERVER_NAME, "server_foo")
                        .with(VitessConnectorConfig.KEYSPACE, AnonymousValue.getString())
                        .with(VitessConnectorConfig.SHARD, AnonymousValue.getString())
                        .with(VitessConnectorConfig.VTCTLD_HOST, AnonymousValue.getString())
                        .with(VitessConnectorConfig.VTCTLD_PORT, AnonymousValue.getInt())
                        .with(VitessConnectorConfig.VTGATE_HOST, AnonymousValue.getString())
                        .with(VitessConnectorConfig.VTGATE_PORT, AnonymousValue.getInt())
                        .build());
        source = new SourceInfo(connectorConfig);
        source.initialVgtid(
                Vgtid.of("k", "s", "MySQL56/a790d864-9ba1-11ea-99f6-0242ac11000a:1-2"),
                Instant.ofEpochMilli(1000));
        source.setTableId(new TableId("c", "s", "t"));
        source.setSnapshot(SnapshotRecord.FALSE);
    }

    @Test
    public void versionIsPresent() {
        assertThat(source.struct().getString(SourceInfo.DEBEZIUM_VERSION_KEY))
                .isEqualTo(Module.version());
    }

    @Test
    public void connectorIsPresent() {
        assertThat(source.struct().getString(SourceInfo.DEBEZIUM_CONNECTOR_KEY))
                .isEqualTo(Module.name());
    }

    @Test
    public void serverNameIsPresent() {
        assertThat(source.struct().getString(SourceInfo.SERVER_NAME_KEY)).isEqualTo("server_foo");
    }

    @Test
    public void vgtidKeyspaceIsPresent() {
        assertThat(source.struct().getString(SourceInfo.VGTID_KEYSPACE)).isEqualTo("k");
    }

    @Test
    public void vgtidShardIsPresent() {
        assertThat(source.struct().getString(SourceInfo.VGTID_SHARD)).isEqualTo("s");
    }

    @Test
    public void vgtidGtidIsPresent() {
        assertThat(source.struct().getString(SourceInfo.VGTID_GTID))
                .isEqualTo("MySQL56/a790d864-9ba1-11ea-99f6-0242ac11000a:1-2");
    }

    @Test
    public void snapshotIsNotPresent() {
        // If SnapshotRecord.FALSE, no snapshot is present
        assertThat(source.struct().getString(SourceInfo.SNAPSHOT_KEY)).isNull();
    }

    @Test
    public void timestampIsPresent() {
        assertThat(source.struct().getInt64(SourceInfo.TIMESTAMP_KEY)).isEqualTo(1000);
    }

    @Test
    public void tableIdIsPresent() {
        assertThat(source.struct().getString(SourceInfo.DATABASE_NAME_KEY)).isEqualTo("server_foo");
        assertThat(source.struct().getString(SourceInfo.SCHEMA_NAME_KEY)).isEqualTo("s");
        assertThat(source.struct().getString(SourceInfo.TABLE_NAME_KEY)).isEqualTo("t");
    }

    @Test
    public void schemaIsCorrect() {
        final Schema schema = SchemaBuilder.struct()
                .name("io.debezium.connector.vitess.Source")
                .field("version", Schema.STRING_SCHEMA)
                .field("connector", Schema.STRING_SCHEMA)
                .field("name", Schema.STRING_SCHEMA)
                .field("ts_ms", Schema.INT64_SCHEMA)
                .field("snapshot", AbstractSourceInfoStructMaker.SNAPSHOT_RECORD_SCHEMA)
                .field("db", Schema.STRING_SCHEMA)
                .field("schema", Schema.STRING_SCHEMA)
                .field("table", Schema.STRING_SCHEMA)
                .field("vgtid_keyspace", Schema.STRING_SCHEMA)
                .field("vgtid_shard", Schema.STRING_SCHEMA)
                .field("vgtid_gtid", Schema.STRING_SCHEMA)
                .build();

        assertThat(source.struct().schema()).isEqualTo(schema);
    }
}