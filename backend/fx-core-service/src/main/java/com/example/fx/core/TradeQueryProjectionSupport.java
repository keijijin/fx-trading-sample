package com.example.fx.core;

import com.example.fx.common.db.ActivityJdbcSupport;
import com.example.fx.common.event.EventTypes;
import com.example.fx.common.kafka.KafkaConsumerUris;
import com.example.fx.common.model.TradePayload;
import com.example.fx.common.outbox.OutboxSupport;
import jakarta.annotation.PostConstruct;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Component
class TradeQueryProjectionSchemaSupport {
    private final JdbcOperations queryJdbcOperations;

    TradeQueryProjectionSchemaSupport(ActivityJdbcSupport activityJdbcSupport) {
        this.queryJdbcOperations = activityJdbcSupport.jdbcOperations();
    }

    @PostConstruct
    public void migrate() {
        queryJdbcOperations.execute(
                "create table if not exists trade_query_projection (" +
                        "trade_id varchar(64) primary key, " +
                        "order_id varchar(64) not null, " +
                        "account_id varchar(64) not null, " +
                        "currency_pair varchar(16) not null, " +
                        "trade_status varchar(32) not null, " +
                        "saga_status varchar(32) not null, " +
                        "correlation_id varchar(128) not null, " +
                        "cover_status varchar(32) not null, " +
                        "risk_status varchar(32) not null, " +
                        "accounting_status varchar(32) not null, " +
                        "settlement_status varchar(32) not null, " +
                        "notification_status varchar(32) not null, " +
                        "compliance_status varchar(32) not null, " +
                        "updated_at timestamp not null)"
        );
        queryJdbcOperations.execute(
                "create table if not exists trade_event_journal (" +
                        "event_id varchar(64) primary key, " +
                        "trade_id varchar(64) not null, " +
                        "source_service varchar(64) not null, " +
                        "event_type varchar(64) not null, " +
                        "topic_name varchar(128) not null, " +
                        "status varchar(32) not null, " +
                        "created_at timestamp not null)"
        );
        queryJdbcOperations.execute(
                "create index if not exists idx_trade_event_journal_trade_id on trade_event_journal(trade_id, created_at)"
        );
    }
}

@Component
class TradeQueryProjectionRoute extends RouteBuilder {
    private final TradeQueryProjectionService tradeQueryProjectionService;
    private final boolean projectionEnabled;
    private final boolean journalEnabled;

    TradeQueryProjectionRoute(
            TradeQueryProjectionService tradeQueryProjectionService,
            @Value("${trade.query.projection.enabled:true}") boolean projectionEnabled,
            @Value("${trade.event.journal.enabled:false}") boolean journalEnabled
    ) {
        this.tradeQueryProjectionService = tradeQueryProjectionService;
        this.projectionEnabled = projectionEnabled;
        this.journalEnabled = journalEnabled;
    }

    @Override
    public void configure() {
        if (!projectionEnabled && !journalEnabled) {
            return;
        }

        from(KafkaConsumerUris.consumer("fx-trade-events", "fx-core-trade-query-events"))
                .routeId("fx-core-trade-query-events")
                .setHeader("topicName", constant("fx-trade-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-cover-events", "fx-core-cover-query-events"))
                .routeId("fx-core-cover-query-events")
                .setHeader("topicName", constant("fx-cover-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-risk-events", "fx-core-risk-query-events"))
                .routeId("fx-core-risk-query-events")
                .setHeader("topicName", constant("fx-risk-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-accounting-events", "fx-core-accounting-query-events"))
                .routeId("fx-core-accounting-query-events")
                .setHeader("topicName", constant("fx-accounting-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-settlement-events", "fx-core-settlement-query-events"))
                .routeId("fx-core-settlement-query-events")
                .setHeader("topicName", constant("fx-settlement-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-notification-events", "fx-core-notification-query-events"))
                .routeId("fx-core-notification-query-events")
                .setHeader("topicName", constant("fx-notification-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-compliance-events", "fx-core-compliance-query-events"))
                .routeId("fx-core-compliance-query-events")
                .setHeader("topicName", constant("fx-compliance-events"))
                .to("direct:projectTradeQueryEvent");

        from(KafkaConsumerUris.consumer("fx-compensation-events", "fx-core-compensation-query-events"))
                .routeId("fx-core-compensation-query-events")
                .setHeader("topicName", constant("fx-compensation-events"))
                .to("direct:projectTradeQueryEvent");

        from("direct:projectTradeQueryEvent")
                .routeId("fx-core-project-trade-query-event")
                .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                .bean(tradeQueryProjectionService, "project(${header.eventType}, ${body}, ${header.eventId}, ${header.topicName})");
    }
}

@Service
class TradeQueryProjectionService {
    private static final String CONSUMER_NAME = "fx-core-trade-query-projection";

    private final JdbcOperations queryJdbcOperations;
    private final OutboxSupport outboxSupport;
    private final boolean projectionEnabled;
    private final boolean journalEnabled;

    TradeQueryProjectionService(
            ActivityJdbcSupport activityJdbcSupport,
            OutboxSupport outboxSupport,
            @Value("${trade.query.projection.enabled:true}") boolean projectionEnabled,
            @Value("${trade.event.journal.enabled:false}") boolean journalEnabled
    ) {
        this.queryJdbcOperations = activityJdbcSupport.jdbcOperations();
        this.outboxSupport = outboxSupport;
        this.projectionEnabled = projectionEnabled;
        this.journalEnabled = journalEnabled;
    }

    @Transactional
    public void project(String eventType, TradePayload payload, String eventId, String topicName) {
        if (outboxSupport.isDuplicate(eventId, CONSUMER_NAME)) {
            return;
        }

        if (projectionEnabled) {
            upsertProjection(eventType, payload);
        }
        if (journalEnabled) {
            appendJournal(eventId, eventType, payload, topicName);
        }
    }

    public TradeProjectionSummary loadSummary(String tradeId) {
        List<TradeProjectionSummary> rows = queryJdbcOperations.query(
                "select trade_id, order_id, account_id, currency_pair, trade_status, saga_status, correlation_id, " +
                        "cover_status, risk_status, accounting_status, settlement_status, notification_status, compliance_status " +
                        "from trade_query_projection where trade_id = ?",
                (rs, rowNum) -> new TradeProjectionSummary(
                        rs.getString("trade_id"),
                        rs.getString("order_id"),
                        rs.getString("account_id"),
                        rs.getString("currency_pair"),
                        rs.getString("trade_status"),
                        rs.getString("saga_status"),
                        rs.getString("correlation_id"),
                        rs.getString("cover_status"),
                        rs.getString("risk_status"),
                        rs.getString("accounting_status"),
                        rs.getString("settlement_status"),
                        rs.getString("notification_status"),
                        rs.getString("compliance_status")
                ),
                tradeId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EventView> loadJournal(String tradeId) {
        return queryJdbcOperations.query(
                "select source_service, event_type, topic_name, status, created_at from trade_event_journal where trade_id = ? order by created_at",
                (rs, rowNum) -> new EventView(
                        rs.getString("source_service"),
                        rs.getString("event_type"),
                        rs.getString("topic_name"),
                        rs.getString("status"),
                        stringifyTimestamp(rs.getTimestamp("created_at")),
                        null
                ),
                tradeId
        );
    }

    public boolean journalEnabled() {
        return journalEnabled;
    }

    private void upsertProjection(String eventType, TradePayload payload) {
        ensureProjectionRow(payload);
        applyEvent(eventType, payload);
        recalculateSagaStatus(payload.tradeId());
    }

    private void ensureProjectionRow(TradePayload payload) {
        queryJdbcOperations.update(
                "insert into trade_query_projection (" +
                        "trade_id, order_id, account_id, currency_pair, trade_status, saga_status, correlation_id, " +
                        "cover_status, risk_status, accounting_status, settlement_status, notification_status, compliance_status, updated_at" +
                        ") values (?, ?, ?, ?, ?, 'PENDING', ?, 'NOT_STARTED', 'NOT_STARTED', 'NOT_STARTED', 'NOT_STARTED', 'NOT_STARTED', 'NOT_STARTED', ?) " +
                        "on conflict (trade_id) do nothing",
                payload.tradeId(),
                payload.orderId(),
                payload.accountId(),
                payload.currencyPair(),
                "EXECUTED",
                payload.correlationId(),
                now()
        );
    }

    private void applyEvent(String eventType, TradePayload payload) {
        Timestamp updatedAt = now();
        switch (eventType) {
            case EventTypes.TRADE_EXECUTED -> updateColumns(payload.tradeId(), updatedAt,
                    "trade_status", "EXECUTED");
            case EventTypes.COVER_TRADE_BOOKED -> updateColumns(payload.tradeId(), updatedAt,
                    "cover_status", "COMPLETED",
                    "risk_status", "PENDING",
                    "accounting_status", "PENDING");
            case EventTypes.COVER_TRADE_FAILED -> updateColumns(payload.tradeId(), updatedAt,
                    "cover_status", "FAILED");
            case EventTypes.RISK_UPDATED -> updateColumns(payload.tradeId(), updatedAt,
                    "risk_status", "COMPLETED");
            case EventTypes.RISK_UPDATE_FAILED -> updateColumns(payload.tradeId(), updatedAt,
                    "risk_status", "FAILED");
            case EventTypes.ACCOUNTING_POSTED -> updateColumns(payload.tradeId(), updatedAt,
                    "accounting_status", "COMPLETED");
            case EventTypes.ACCOUNTING_POST_FAILED -> updateColumns(payload.tradeId(), updatedAt,
                    "accounting_status", "FAILED");
            case EventTypes.SETTLEMENT_START_REQUESTED -> updateColumns(payload.tradeId(), updatedAt,
                    "settlement_status", "PENDING");
            case EventTypes.SETTLEMENT_RESERVED -> updateColumns(payload.tradeId(), updatedAt,
                    "settlement_status", "COMPLETED");
            case EventTypes.SETTLEMENT_RESERVE_FAILED -> updateColumns(payload.tradeId(), updatedAt,
                    "settlement_status", "FAILED");
            case EventTypes.TRADE_NOTIFICATION_SENT -> updateColumns(payload.tradeId(), updatedAt,
                    "notification_status", "COMPLETED");
            case EventTypes.TRADE_NOTIFICATION_FAILED -> updateColumns(payload.tradeId(), updatedAt,
                    "notification_status", "FAILED");
            case EventTypes.COMPLIANCE_CHECKED -> updateColumns(payload.tradeId(), updatedAt,
                    "compliance_status", "COMPLETED");
            case EventTypes.COMPLIANCE_CHECK_FAILED -> updateColumns(payload.tradeId(), updatedAt,
                    "compliance_status", "FAILED");
            case EventTypes.COVER_TRADE_REVERSED -> updateColumns(payload.tradeId(), updatedAt,
                    "cover_status", "COMPENSATED");
            case EventTypes.RISK_REVERSED -> updateColumns(payload.tradeId(), updatedAt,
                    "risk_status", "COMPENSATED");
            case EventTypes.ACCOUNTING_REVERSED -> updateColumns(payload.tradeId(), updatedAt,
                    "accounting_status", "COMPENSATED");
            case EventTypes.SETTLEMENT_CANCELLED -> updateColumns(payload.tradeId(), updatedAt,
                    "settlement_status", "COMPENSATED");
            case EventTypes.CORRECTION_NOTICE_SENT -> updateColumns(payload.tradeId(), updatedAt,
                    "notification_status", "COMPENSATED");
            default -> {
            }
        }
    }

    private void recalculateSagaStatus(String tradeId) {
        TradeProjectionSummary state = loadSummary(tradeId);
        if (state == null) {
            return;
        }

        String sagaStatus;
        if (isFullyCompleted(state)) {
            sagaStatus = "COMPLETED";
        } else if (isCancellationClosed(state)) {
            sagaStatus = "CANCELLED";
        } else if (hasFailureOrCompensation(state)) {
            sagaStatus = "COMPENSATING";
        } else if (hasProgress(state)) {
            sagaStatus = "RUNNING";
        } else {
            sagaStatus = "PENDING";
        }

        queryJdbcOperations.update(
                "update trade_query_projection set saga_status = ?, updated_at = ? where trade_id = ?",
                sagaStatus,
                now(),
                tradeId
        );
    }

    private boolean isFullyCompleted(TradeProjectionSummary state) {
        return "COMPLETED".equals(state.coverStatus())
                && "COMPLETED".equals(state.riskStatus())
                && "COMPLETED".equals(state.accountingStatus())
                && "COMPLETED".equals(state.settlementStatus())
                && "COMPLETED".equals(state.complianceStatus())
                && isNotificationClosed(state.notificationStatus());
    }

    private boolean isCancellationClosed(TradeProjectionSummary state) {
        return hasFailureOrCompensation(state)
                && isCompensationClosed(state.coverStatus())
                && isCompensationClosed(state.riskStatus())
                && isCompensationClosed(state.accountingStatus())
                && isCompensationClosed(state.settlementStatus())
                && isNotificationCompensationClosed(state.notificationStatus())
                && isCompensationClosed(state.complianceStatus());
    }

    private boolean hasFailureOrCompensation(TradeProjectionSummary state) {
        return containsFailureOrCompensation(state.coverStatus())
                || containsFailureOrCompensation(state.riskStatus())
                || containsFailureOrCompensation(state.accountingStatus())
                || containsFailureOrCompensation(state.settlementStatus())
                || containsFailureOrCompensation(state.notificationStatus())
                || containsFailureOrCompensation(state.complianceStatus());
    }

    private boolean hasProgress(TradeProjectionSummary state) {
        return !"NOT_STARTED".equals(state.coverStatus())
                || !"NOT_STARTED".equals(state.riskStatus())
                || !"NOT_STARTED".equals(state.accountingStatus())
                || !"NOT_STARTED".equals(state.settlementStatus())
                || !"NOT_STARTED".equals(state.notificationStatus())
                || !"NOT_STARTED".equals(state.complianceStatus());
    }

    private boolean containsFailureOrCompensation(String status) {
        return "FAILED".equals(status) || "COMPENSATED".equals(status);
    }

    private boolean isNotificationClosed(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "COMPENSATED".equals(status);
    }

    private boolean isCompensationClosed(String status) {
        return "NOT_STARTED".equals(status) || "FAILED".equals(status) || "COMPENSATED".equals(status) || "COMPLETED".equals(status);
    }

    private boolean isNotificationCompensationClosed(String status) {
        return "NOT_STARTED".equals(status) || "FAILED".equals(status) || "COMPENSATED".equals(status) || "COMPLETED".equals(status);
    }

    private void updateColumns(String tradeId, Timestamp updatedAt, String... assignments) {
        StringBuilder sql = new StringBuilder("update trade_query_projection set ");
        Object[] args = new Object[(assignments.length / 2) + 2];
        int argIndex = 0;
        for (int index = 0; index < assignments.length; index += 2) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append(assignments[index]).append(" = ?");
            args[argIndex++] = assignments[index + 1];
        }
        sql.append(", updated_at = ? where trade_id = ?");
        args[argIndex++] = updatedAt;
        args[argIndex] = tradeId;
        queryJdbcOperations.update(sql.toString(), args);
    }

    private void appendJournal(String eventId, String eventType, TradePayload payload, String topicName) {
        queryJdbcOperations.update(
                "insert into trade_event_journal (event_id, trade_id, source_service, event_type, topic_name, status, created_at) " +
                        "values (?, ?, ?, ?, ?, 'CONSUMED', ?) on conflict (event_id) do nothing",
                eventId,
                payload.tradeId(),
                sourceService(eventType),
                eventType,
                topicName,
                now()
        );
    }

    private String sourceService(String eventType) {
        return switch (eventType) {
            case EventTypes.TRADE_EXECUTED -> "fx-core-service";
            case EventTypes.COVER_TRADE_BOOKED, EventTypes.COVER_TRADE_FAILED, EventTypes.COVER_TRADE_REVERSED,
                    EventTypes.REVERSE_COVER_TRADE_REQUESTED -> "cover-service";
            case EventTypes.RISK_UPDATED, EventTypes.RISK_UPDATE_FAILED, EventTypes.RISK_REVERSED,
                    EventTypes.REVERSE_RISK_REQUESTED -> "risk-service";
            case EventTypes.ACCOUNTING_POSTED, EventTypes.ACCOUNTING_POST_FAILED, EventTypes.ACCOUNTING_REVERSED,
                    EventTypes.REVERSE_ACCOUNTING_REQUESTED -> "accounting-service";
            case EventTypes.SETTLEMENT_START_REQUESTED, EventTypes.SETTLEMENT_RESERVED, EventTypes.SETTLEMENT_RESERVE_FAILED,
                    EventTypes.SETTLEMENT_CANCELLED, EventTypes.CANCEL_SETTLEMENT_REQUESTED -> "settlement-service";
            case EventTypes.TRADE_NOTIFICATION_SENT, EventTypes.TRADE_NOTIFICATION_FAILED,
                    EventTypes.CORRECTION_NOTICE_SENT, EventTypes.SEND_CORRECTION_NOTICE_REQUESTED -> "notification-service";
            case EventTypes.COMPLIANCE_CHECKED, EventTypes.COMPLIANCE_CHECK_FAILED -> "compliance-service";
            default -> "unknown";
        };
    }

    private String stringifyTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}

record TradeProjectionSummary(
        String tradeId,
        String orderId,
        String accountId,
        String currencyPair,
        String tradeStatus,
        String sagaStatus,
        String correlationId,
        String coverStatus,
        String riskStatus,
        String accountingStatus,
        String settlementStatus,
        String notificationStatus,
        String complianceStatus
) {
}
