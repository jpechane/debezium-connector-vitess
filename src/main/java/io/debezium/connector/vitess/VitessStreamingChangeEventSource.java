/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.vitess;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.vitess.connection.ReplicationConnection;
import io.debezium.connector.vitess.connection.ReplicationMessage;
import io.debezium.connector.vitess.connection.ReplicationMessageProcessor;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.StreamingChangeEventSource;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;
import io.debezium.util.DelayStrategy;

/**
 * Read events from source and dispatch each event using {@link EventDispatcher} to the {@link
 * io.debezium.pipeline.source.spi.ChangeEventSource}. It runs in the
 * change-event-source-coordinator thread only.
 */
public class VitessStreamingChangeEventSource implements StreamingChangeEventSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(VitessStreamingChangeEventSource.class);

    private final EventDispatcher<TableId> dispatcher;
    private final ErrorHandler errorHandler;
    private final Clock clock;
    private final VitessDatabaseSchema schema;
    private final VitessOffsetContext offsetContext;
    private final VitessConnectorConfig connectorConfig;
    private final ReplicationConnection replicationConnection;
    private final DelayStrategy pauseNoMessage;

    public VitessStreamingChangeEventSource(
                                            EventDispatcher<TableId> dispatcher,
                                            ErrorHandler errorHandler,
                                            Clock clock,
                                            VitessDatabaseSchema schema,
                                            VitessOffsetContext offsetContext,
                                            VitessConnectorConfig connectorConfig,
                                            ReplicationConnection replicationConnection) {
        this.dispatcher = dispatcher;
        this.errorHandler = errorHandler;
        this.clock = clock;
        this.schema = schema;
        this.offsetContext = offsetContext != null
                ? offsetContext
                : VitessOffsetContext.initialContext(connectorConfig, clock);
        this.connectorConfig = connectorConfig;
        this.replicationConnection = replicationConnection;
        this.pauseNoMessage = DelayStrategy.constant(connectorConfig.getPollInterval().toMillis());

        LOGGER.info("VitessStreamingChangeEventSource is created with offsetContext: {}", offsetContext);
    }

    @Override
    public void execute(ChangeEventSourceContext context) {
        try {
            AtomicReference<Throwable> error = new AtomicReference<>();
            replicationConnection.startStreaming(
                    offsetContext.getRestartVgtid(), newReplicationMessageProcessor(), error);

            while (context.isRunning() && error.get() == null) {
                pauseNoMessage.sleepWhen(true);
            }
            if (error.get() != null) {
                LOGGER.error("Error during streaming", error.get());
                throw new RuntimeException(error.get());
            }
        }
        catch (Throwable e) {
            errorHandler.setProducerThrowable(e);
        }
        finally {
            try {
                // closing the connection should also disconnect the VStream gRPC channel
                replicationConnection.close();
            }
            catch (Exception e) {
                LOGGER.error("Failed to close replicationConnection", e);
            }
        }
    }

    private ReplicationMessageProcessor newReplicationMessageProcessor() {
        return (message, newVgtid) -> {
            if (message.isTransactionalMessage()) {
                // Tx BEGIN/END event
                offsetContext.rotateVgtid(newVgtid, message.getCommitTime());
                if (message.getOperation() == ReplicationMessage.Operation.BEGIN) {
                    // send to transaction topic
                    dispatcher.dispatchTransactionStartedEvent(message.getTransactionId(), offsetContext);
                }
                else if (message.getOperation() == ReplicationMessage.Operation.COMMIT) {
                    // send to transaction topic
                    dispatcher.dispatchTransactionCommittedEvent(offsetContext);
                }
                return;
            }
            else if (message.getOperation() == ReplicationMessage.Operation.DDL || message.getOperation() == ReplicationMessage.Operation.OTHER) {
                // DDL event or OTHER event
                offsetContext.rotateVgtid(newVgtid, message.getCommitTime());
            }
            else {
                // DML event
                TableId tableId = VitessDatabaseSchema.parse(message.getTable());
                Objects.requireNonNull(tableId);

                if (offsetContext.isSkipEvent()) {
                    LOGGER.info(
                            "Skipping event {} from {}, initialEventsToSkip = {}",
                            message,
                            newVgtid,
                            offsetContext.getInitialEventsToSkip());
                    offsetContext.startRowEvent(message.getCommitTime(), tableId);
                    return;
                }
                else {
                    offsetContext.startRowEvent(message.getCommitTime(), tableId);
                    // Update offset position
                    dispatcher.dispatchDataChangeEvent(
                            tableId,
                            new VitessChangeRecordEmitter(
                                    offsetContext, clock, connectorConfig, schema, message));
                }
            }
        };
    }
}
