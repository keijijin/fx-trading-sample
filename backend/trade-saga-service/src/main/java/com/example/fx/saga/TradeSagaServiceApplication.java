package com.example.fx.saga;

import com.example.fx.common.activity.TradeActivitySupport;
import com.example.fx.common.event.EventTypes;
import com.example.fx.common.kafka.KafkaConsumerUris;
import com.example.fx.common.json.JsonSupport;
import com.example.fx.common.model.TradePayload;
import com.example.fx.common.outbox.AbstractOutboxPublisherRoute;
import com.example.fx.common.outbox.OutboxSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@SpringBootApplication(scanBasePackages = {"com.example.fx.saga", "com.example.fx.common"})
public class TradeSagaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradeSagaServiceApplication.class, args);
    }
}

@Component
class TradeSagaRoute extends RouteBuilder {
    private final TradeSagaService tradeSagaService;

    TradeSagaRoute(TradeSagaService tradeSagaService) {
        this.tradeSagaService = tradeSagaService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-trade-events", "trade-saga-service-seed"))
                .routeId("trade-saga-trade-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.TRADE_EXECUTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(tradeSagaService, "seed(${body}, ${header.eventId})")
                .end();

        from(KafkaConsumerUris.consumer("fx-cover-events", "trade-saga-fx-cover-events"))
                .routeId("trade-saga-cover-events")
                .to("direct:handleTradeSagaEvent");

        from(KafkaConsumerUris.consumer("fx-risk-events", "trade-saga-fx-risk-events"))
                .routeId("trade-saga-risk-events")
                .to("direct:handleTradeSagaEvent");

        from(KafkaConsumerUris.consumer("fx-accounting-events", "trade-saga-fx-accounting-events"))
                .routeId("trade-saga-accounting-events")
                .to("direct:handleTradeSagaEvent");

        from(KafkaConsumerUris.consumer("fx-settlement-events", "trade-saga-fx-settlement-events"))
                .routeId("trade-saga-settlement-events")
                .to("direct:handleTradeSagaEvent");

        from(KafkaConsumerUris.consumer("fx-notification-events", "trade-saga-fx-notification-events"))
                .routeId("trade-saga-notification-events")
                .to("direct:handleTradeSagaEvent");

        from(KafkaConsumerUris.consumer("fx-compliance-events", "trade-saga-fx-compliance-events"))
                .routeId("trade-saga-compliance-events")
                .to("direct:handleTradeSagaEvent");

        from("direct:handleTradeSagaEvent")
                .routeId("trade-saga-handle-event")
                .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                .bean(tradeSagaService, "handle(${header.eventType}, ${body}, ${header.eventId})");
    }
}

@Component
class TradeSagaOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    TradeSagaOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "trade-saga-service", "tradeSagaOutboxPoller");
    }
}

@Component
class TradeSagaSchemaSupport {
    private final JdbcTemplate jdbcTemplate;

    TradeSagaSchemaSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute("alter table trade_saga add column if not exists cover_completed_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists risk_completed_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists accounting_completed_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists settlement_requested_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists settlement_completed_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists notification_completed_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists compliance_completed_at timestamp null");
        jdbcTemplate.execute("alter table trade_saga add column if not exists saga_completed_at timestamp null");
        jdbcTemplate.execute(
                "create index if not exists idx_trade_activity_trade_id on trade_activity(trade_id)");
    }
}

@org.springframework.stereotype.Service
class TradeSagaService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;
    private final MeterRegistry meterRegistry;

    TradeSagaService(
            JdbcTemplate jdbcTemplate,
            OutboxSupport outboxSupport,
            JsonSupport jsonSupport,
            TradeActivitySupport tradeActivitySupport,
            MeterRegistry meterRegistry
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxSupport = outboxSupport;
        this.jsonSupport = jsonSupport;
        this.tradeActivitySupport = tradeActivitySupport;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void handle(String eventType, TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "trade-saga-service")) {
            return;
        }

        ensureSagaExists(payload);

        tradeActivitySupport.record(
                payload.tradeId(),
                "trade-saga-service",
                "EVENT_CONSUMED",
                "RUNNING",
                "Trade Saga consumed event " + eventType,
                eventType,
                sourceTopic(eventType)
        );

        switch (eventType) {
            case EventTypes.COVER_TRADE_BOOKED -> {
                updateStepStatus(payload.tradeId(), "cover_status", "COMPLETED", "cover_completed_at");
                updateStepStatus(payload.tradeId(), "risk_status", "PENDING");
                updateStepStatus(payload.tradeId(), "accounting_status", "PENDING");
                recordStageDuration(payload.tradeId(), "cover", "completed", "created_at", "cover_completed_at");
            }
            case EventTypes.COVER_TRADE_FAILED -> {
                updateStepStatus(payload.tradeId(), "cover_status", "FAILED", "cover_completed_at");
                recordStageDuration(payload.tradeId(), "cover", "failed", "created_at", "cover_completed_at");
                startCompensation(payload, true);
            }
            case EventTypes.RISK_UPDATED -> {
                updateStepStatus(payload.tradeId(), "risk_status", "COMPLETED", "risk_completed_at");
                recordStageDuration(payload.tradeId(), "risk", "completed", "cover_completed_at", "risk_completed_at");
                maybeRequestSettlement(payload);
                enqueueDeferredReverseRiskIfNeeded(payload);
            }
            case EventTypes.RISK_UPDATE_FAILED -> {
                updateStepStatus(payload.tradeId(), "risk_status", "FAILED", "risk_completed_at");
                recordStageDuration(payload.tradeId(), "risk", "failed", "cover_completed_at", "risk_completed_at");
                startCompensation(payload, false);
            }
            case EventTypes.ACCOUNTING_POSTED -> {
                updateStepStatus(payload.tradeId(), "accounting_status", "COMPLETED", "accounting_completed_at");
                recordStageDuration(payload.tradeId(), "accounting", "completed", "cover_completed_at", "accounting_completed_at");
                maybeRequestSettlement(payload);
            }
            case EventTypes.ACCOUNTING_POST_FAILED -> {
                updateStepStatus(payload.tradeId(), "accounting_status", "FAILED", "accounting_completed_at");
                recordStageDuration(payload.tradeId(), "accounting", "failed", "cover_completed_at", "accounting_completed_at");
                startCompensation(payload, false);
            }
            case EventTypes.SETTLEMENT_RESERVED -> {
                updateStepStatus(payload.tradeId(), "settlement_status", "COMPLETED", "settlement_completed_at");
                recordStageDuration(payload.tradeId(), "settlement", "completed", "settlement_requested_at", "settlement_completed_at");
                completeIfReady(payload.tradeId(), "settlement_reserved");
            }
            case EventTypes.SETTLEMENT_RESERVE_FAILED -> {
                updateStepStatus(payload.tradeId(), "settlement_status", "FAILED", "settlement_completed_at");
                recordStageDuration(payload.tradeId(), "settlement", "failed", "settlement_requested_at", "settlement_completed_at");
                startCompensation(payload, false);
            }
            case EventTypes.TRADE_NOTIFICATION_SENT -> {
                updateStepStatus(payload.tradeId(), "notification_status", "COMPLETED", "notification_completed_at");
                recordStageDuration(payload.tradeId(), "notification", "completed", "created_at", "notification_completed_at");
                completeIfReady(payload.tradeId(), "notification_sent");
            }
            case EventTypes.TRADE_NOTIFICATION_FAILED -> {
                updateStepStatus(payload.tradeId(), "notification_status", "FAILED", "notification_completed_at");
                recordStageDuration(payload.tradeId(), "notification", "failed", "created_at", "notification_completed_at");
                completeIfReady(payload.tradeId(), "notification_failed");
            }
            case EventTypes.COMPLIANCE_CHECKED -> {
                updateStepStatus(payload.tradeId(), "compliance_status", "COMPLETED", "compliance_completed_at");
                recordStageDuration(payload.tradeId(), "compliance", "completed", "created_at", "compliance_completed_at");
                completeIfReady(payload.tradeId(), "compliance_checked");
            }
            case EventTypes.COMPLIANCE_CHECK_FAILED -> {
                updateStepStatus(payload.tradeId(), "compliance_status", "FAILED", "compliance_completed_at");
                recordStageDuration(payload.tradeId(), "compliance", "failed", "created_at", "compliance_completed_at");
                startCompensation(payload, false);
            }
            case EventTypes.COVER_TRADE_REVERSED -> {
                updateStepStatus(payload.tradeId(), "cover_status", "COMPENSATED");
                cancelIfReady(payload.tradeId());
            }
            case EventTypes.RISK_REVERSED -> {
                updateStepStatus(payload.tradeId(), "risk_status", "COMPENSATED");
                cancelIfReady(payload.tradeId());
            }
            case EventTypes.ACCOUNTING_REVERSED -> {
                updateStepStatus(payload.tradeId(), "accounting_status", "COMPENSATED");
                cancelIfReady(payload.tradeId());
            }
            case EventTypes.SETTLEMENT_CANCELLED -> {
                updateStepStatus(payload.tradeId(), "settlement_status", "COMPENSATED");
                cancelIfReady(payload.tradeId());
            }
            case EventTypes.CORRECTION_NOTICE_SENT -> {
                updateStepStatus(payload.tradeId(), "notification_status", "COMPENSATED");
                cancelIfReady(payload.tradeId());
            }
            default -> {
            }
        }
    }

    @Transactional
    public void seed(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "trade-saga-service-seed")) {
            return;
        }
        if (ensureSagaExists(payload)) {
            tradeActivitySupport.record(
                    payload.tradeId(),
                    "trade-saga-service",
                    "SAGA_SEEDED",
                    "PENDING",
                    "Trade saga row created from TradeExecuted",
                    EventTypes.TRADE_EXECUTED,
                    "fx-trade-events"
            );
        }
    }

    private void maybeRequestSettlement(TradePayload payload) {
        Map<String, Object> state = loadSaga(payload.tradeId());
        String riskStatus = stringValue(state, "risk_status");
        String accountingStatus = stringValue(state, "accounting_status");
        String settlementStatus = stringValue(state, "settlement_status");
        if ("COMPLETED".equals(riskStatus)
                && "COMPLETED".equals(accountingStatus)
                && "NOT_STARTED".equals(settlementStatus)) {
            updateStepStatus(payload.tradeId(), "settlement_status", "PENDING", "settlement_requested_at");
            tradeActivitySupport.record(
                    payload.tradeId(),
                    "trade-saga-service",
                    "SETTLEMENT_REQUESTED",
                    "PENDING",
                    "Risk and Accounting completed; requesting settlement",
                    EventTypes.SETTLEMENT_START_REQUESTED,
                    "fx-settlement-events"
            );
            enqueue(
                    payload,
                    EventTypes.SETTLEMENT_START_REQUESTED,
                    "fx-settlement-events",
                    "trade_saga",
                    payload.tradeId()
            );
        }
    }

    private void completeIfReady(String tradeId, String trigger) {
        long startedAt = System.nanoTime();
        Timestamp completionTime = now();
        Map<String, Object> completionRow = tryCompleteSaga(tradeId, completionTime);
        recordCloseOutAttempt(trigger, completionRow != null, startedAt);
        if (completionRow == null) {
            return;
        }
        tradeActivitySupport.record(
                tradeId,
                "trade-saga-service",
                "SAGA_COMPLETED",
                "COMPLETED",
                "All required downstream services completed",
                null,
                null
        );
        Counter.builder("fx_saga_completed_total").register(meterRegistry).increment();
        recordCloseOutDelay(completionRow, completionTime);
        recordSagaDuration(completionRow, "completed");
    }

    private void startCompensation(TradePayload payload, boolean requestCorrectionNotice) {
        Map<String, Object> state = loadSaga(payload.tradeId());
        jdbcTemplate.update(
                "update trade_saga set saga_status = 'COMPENSATING', updated_at = ?, version_no = version_no + 1 where trade_id = ?",
                now(),
                payload.tradeId()
        );
        tradeActivitySupport.record(
                payload.tradeId(),
                "trade-saga-service",
                "COMPENSATION_STARTED",
                "COMPENSATING",
                "Trade Saga entered compensation mode",
                null,
                null
        );
        Counter.builder("fx_compensation_started_total").register(meterRegistry).increment();

        requestIfCompleted(state, "cover_status", EventTypes.REVERSE_COVER_TRADE_REQUESTED, "fx-compensation-events", payload);
        requestIfCompleted(state, "risk_status", EventTypes.REVERSE_RISK_REQUESTED, "fx-compensation-events", payload);
        requestIfCompleted(state, "accounting_status", EventTypes.REVERSE_ACCOUNTING_REQUESTED, "fx-compensation-events", payload);
        requestIfCompleted(state, "settlement_status", EventTypes.CANCEL_SETTLEMENT_REQUESTED, "fx-compensation-events", payload);

        markCancelRequestedIfPending(payload.tradeId(), state, "cover_status", "cover_cancel_requested");
        markCancelRequestedIfPending(payload.tradeId(), state, "risk_status", "risk_cancel_requested");
        markCancelRequestedIfPending(payload.tradeId(), state, "accounting_status", "accounting_cancel_requested");
        markCancelRequestedIfPending(payload.tradeId(), state, "settlement_status", "settlement_cancel_requested");

        if (requestCorrectionNotice || isNotificationClosed(stringValue(state, "notification_status"))) {
            enqueue(
                    payload,
                    EventTypes.SEND_CORRECTION_NOTICE_REQUESTED,
                    "fx-compensation-events",
                    "trade_saga",
                    payload.tradeId()
            );
        }
    }

    private void requestIfCompleted(
            Map<String, Object> state,
            String column,
            String eventType,
            String topic,
            TradePayload payload
    ) {
        if ("COMPLETED".equals(stringValue(state, column))) {
            enqueue(payload, eventType, topic, "trade_saga", payload.tradeId());
        }
    }

    private void markCancelRequestedIfPending(String tradeId, Map<String, Object> state, String statusColumn, String flagColumn) {
        if ("PENDING".equals(stringValue(state, statusColumn))) {
            jdbcTemplate.update(
                    "update trade_saga set " + flagColumn + " = true, updated_at = ?, version_no = version_no + 1 where trade_id = ?",
                    now(),
                    tradeId
            );
        }
    }

    /**
     * If accounting failed before risk finished processing (parallel consumers on fx-cover-events),
     * {@link #startCompensation} could not enqueue {@link EventTypes#REVERSE_RISK_REQUESTED} because
     * {@code risk_status} was still PENDING. When risk later completes, enqueue the deferred reverse once.
     */
    private void enqueueDeferredReverseRiskIfNeeded(TradePayload payload) {
        int updated = jdbcTemplate.update(
                "update trade_saga set risk_cancel_requested = false, updated_at = ?, version_no = version_no + 1 "
                        + "where trade_id = ? and saga_status = 'COMPENSATING' and risk_cancel_requested = true "
                        + "and risk_status = 'COMPLETED'",
                now(),
                payload.tradeId()
        );
        if (updated == 1) {
            Counter.builder("fx_saga_deferred_reverse_risk_enqueued_total")
                    .description("REVERSE_RISK_REQUESTED enqueued after risk completed while compensating (parallel consumer ordering)")
                    .register(meterRegistry)
                    .increment();
            enqueue(payload, EventTypes.REVERSE_RISK_REQUESTED, "fx-compensation-events", "trade_saga", payload.tradeId());
        }
    }

    private void cancelIfReady(String tradeId) {
        Map<String, Object> state = loadSaga(tradeId);
        boolean allClosed =
                isCompensationClosed(stringValue(state, "cover_status"))
                        && isCompensationClosed(stringValue(state, "risk_status"))
                        && isCompensationClosed(stringValue(state, "accounting_status"))
                        && isCompensationClosed(stringValue(state, "settlement_status"))
                        && isNotificationCompensationClosed(stringValue(state, "notification_status"));
        if (allClosed) {
            jdbcTemplate.update(
                    "update trade_saga set saga_status = 'CANCELLED', updated_at = ?, version_no = version_no + 1 where trade_id = ?",
                    now(),
                    tradeId
            );
            tradeActivitySupport.record(
                    tradeId,
                    "trade-saga-service",
                    "SAGA_CANCELLED",
                    "CANCELLED",
                    "Compensation completed and saga cancelled",
                    null,
                    null
            );
            Counter.builder("fx_saga_cancelled_total").register(meterRegistry).increment();
            recordSagaDuration(state, "cancelled");
        }
    }

    private void updateStepStatus(String tradeId, String column, String status) {
        updateStepStatus(tradeId, column, status, null);
    }

    private void updateStepStatus(String tradeId, String column, String status, String timestampColumn) {
        if (timestampColumn == null) {
            jdbcTemplate.update(
                    "update trade_saga set " + column + " = ?, updated_at = ?, version_no = version_no + 1 where trade_id = ?",
                    status,
                    now(),
                    tradeId
            );
            return;
        }
        Timestamp current = now();
        jdbcTemplate.update(
                "update trade_saga set " + column + " = ?, " + timestampColumn + " = ?, updated_at = ?, version_no = version_no + 1 where trade_id = ?",
                status,
                current,
                current,
                tradeId
        );
    }

    private void enqueue(TradePayload payload, String eventType, String topic, String aggregateType, String aggregateId) {
        outboxSupport.enqueue(
                "trade-saga-service",
                aggregateType,
                aggregateId,
                eventType,
                topic,
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Map<String, Object> loadSaga(String tradeId) {
        return jdbcTemplate.queryForMap("select * from trade_saga where trade_id = ?", tradeId);
    }

    private boolean ensureSagaExists(TradePayload payload) {
        return jdbcTemplate.update(
                "insert into trade_saga (" +
                        "trade_id, order_id, saga_status, cover_status, risk_status, accounting_status, settlement_status, " +
                        "notification_status, compliance_status, cover_cancel_requested, risk_cancel_requested, " +
                        "accounting_cancel_requested, settlement_cancel_requested, correlation_id, created_at, updated_at, version_no" +
                        ") values (?, ?, 'PENDING', 'NOT_STARTED', 'NOT_STARTED', 'NOT_STARTED', 'NOT_STARTED', " +
                        "'NOT_STARTED', 'NOT_STARTED', false, false, false, false, ?, ?, ?, 0) " +
                        "on conflict (trade_id) do nothing",
                payload.tradeId(),
                payload.orderId(),
                payload.correlationId(),
                now(),
                now()
        ) == 1;
    }

    private Map<String, Object> tryCompleteSaga(String tradeId, Timestamp completionTime) {
        return jdbcTemplate.query(
                "with candidate as (" +
                        "select trade_id, created_at, " +
                        "greatest(" +
                        "coalesce(settlement_completed_at, created_at), " +
                        "coalesce(notification_completed_at, created_at), " +
                        "coalesce(compliance_completed_at, created_at)) as close_out_ready_at " +
                        "from trade_saga " +
                        "where trade_id = ? " +
                        "and cover_status = 'COMPLETED' " +
                        "and risk_status = 'COMPLETED' " +
                        "and accounting_status = 'COMPLETED' " +
                        "and settlement_status = 'COMPLETED' " +
                        "and compliance_status = 'COMPLETED' " +
                        "and notification_status in ('COMPLETED', 'FAILED', 'COMPENSATED') " +
                        "and saga_status <> 'COMPLETED'), " +
                        "updated as (" +
                        "update trade_saga set saga_status = 'COMPLETED', saga_completed_at = ?, updated_at = ?, version_no = version_no + 1 " +
                        "where trade_id in (select trade_id from candidate) " +
                        "returning trade_id) " +
                        "select candidate.trade_id, candidate.created_at, candidate.close_out_ready_at " +
                        "from candidate join updated on updated.trade_id = candidate.trade_id",
                rs -> rs.next()
                        ? Map.of(
                        "trade_id", rs.getString("trade_id"),
                        "created_at", rs.getTimestamp("created_at"),
                        "close_out_ready_at", rs.getTimestamp("close_out_ready_at")
                )
                        : null,
                tradeId,
                completionTime,
                completionTime
        );
    }

    private boolean isNotificationClosed(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status) || "COMPENSATED".equals(status);
    }

    private boolean isCompensationClosed(String status) {
        return "NOT_STARTED".equals(status) || "SKIPPED".equals(status) || "COMPENSATED".equals(status) || "FAILED".equals(status);
    }

    private boolean isNotificationCompensationClosed(String status) {
        return "NOT_STARTED".equals(status) || "FAILED".equals(status) || "COMPENSATED".equals(status) || "COMPLETED".equals(status);
    }

    private String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private void recordStageDuration(String tradeId, String stage, String outcome, String startColumn, String endColumn) {
        Map<String, Object> state = loadSaga(tradeId);
        Timestamp start = (Timestamp) state.get(startColumn);
        Timestamp end = (Timestamp) state.get(endColumn);
        if (start == null || end == null) {
            return;
        }
        Duration duration = Duration.between(start.toInstant(), end.toInstant());
        Timer.builder("fx_trade_saga_stage_duration_seconds")
                .publishPercentileHistogram()
                .tag("stage", stage)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    private void recordSagaDuration(Map<String, Object> state, String outcome) {
        Object createdAtValue = state.get("created_at");
        if (!(createdAtValue instanceof Timestamp createdAt)) {
            return;
        }
        Duration duration = Duration.between(createdAt.toInstant(), Instant.now());
        Timer.builder("fx_trade_saga_duration_seconds")
                .publishPercentileHistogram()
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(duration);
    }

    private void recordCloseOutAttempt(String trigger, boolean completed, long startedAt) {
        Timer.builder("fx_trade_saga_close_out_duration_seconds")
                .publishPercentileHistogram()
                .tag("trigger", trigger)
                .tag("completed", Boolean.toString(completed))
                .register(meterRegistry)
                .record(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private void recordCloseOutDelay(Map<String, Object> state, Timestamp completionTime) {
        Object readyAtValue = state.get("close_out_ready_at");
        if (!(readyAtValue instanceof Timestamp readyAt)) {
            return;
        }
        Duration duration = Duration.between(readyAt.toInstant(), completionTime.toInstant());
        Timer.builder("fx_trade_saga_close_out_wait_seconds")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    private String sourceTopic(String eventType) {
        return switch (eventType) {
            case EventTypes.COVER_TRADE_BOOKED, EventTypes.COVER_TRADE_FAILED, EventTypes.COVER_TRADE_REVERSED -> "fx-cover-events";
            case EventTypes.RISK_UPDATED, EventTypes.RISK_UPDATE_FAILED, EventTypes.RISK_REVERSED -> "fx-risk-events";
            case EventTypes.ACCOUNTING_POSTED, EventTypes.ACCOUNTING_POST_FAILED, EventTypes.ACCOUNTING_REVERSED -> "fx-accounting-events";
            case EventTypes.SETTLEMENT_RESERVED, EventTypes.SETTLEMENT_RESERVE_FAILED, EventTypes.SETTLEMENT_CANCELLED -> "fx-settlement-events";
            case EventTypes.TRADE_NOTIFICATION_SENT, EventTypes.TRADE_NOTIFICATION_FAILED, EventTypes.CORRECTION_NOTICE_SENT -> "fx-notification-events";
            case EventTypes.COMPLIANCE_CHECKED, EventTypes.COMPLIANCE_CHECK_FAILED -> "fx-compliance-events";
            default -> null;
        };
    }
}
