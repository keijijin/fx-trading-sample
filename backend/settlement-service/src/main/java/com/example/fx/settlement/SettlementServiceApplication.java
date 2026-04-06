package com.example.fx.settlement;

import com.example.fx.common.activity.TradeActivitySupport;
import com.example.fx.common.event.EventTypes;
import com.example.fx.common.kafka.KafkaConsumerUris;
import com.example.fx.common.json.JsonSupport;
import com.example.fx.common.model.TradePayload;
import com.example.fx.common.outbox.AbstractOutboxPublisherRoute;
import com.example.fx.common.outbox.OutboxSupport;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@SpringBootApplication(scanBasePackages = {"com.example.fx.settlement", "com.example.fx.common"})
public class SettlementServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SettlementServiceApplication.class, args);
    }
}

@Component
class SettlementConsumerRoute extends RouteBuilder {
    private final SettlementProcessingService settlementService;

    SettlementConsumerRoute(SettlementProcessingService settlementService) {
        this.settlementService = settlementService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-settlement-events", "settlement-service"))
                .routeId("settlement-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.SETTLEMENT_START_REQUESTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(settlementService, "reserve(${body}, ${header.eventId})")
                .end();

        from(KafkaConsumerUris.consumer("fx-compensation-events", "settlement-service-comp"))
                .routeId("settlement-comp-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.CANCEL_SETTLEMENT_REQUESTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(settlementService, "cancel(${body}, ${header.eventId})")
                .end();
    }
}

@Component
class SettlementOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    SettlementOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "settlement-service", "settlementOutboxPoller");
    }
}

@org.springframework.stereotype.Service
class SettlementProcessingService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;

    SettlementProcessingService(
            JdbcTemplate jdbcTemplate,
            OutboxSupport outboxSupport,
            JsonSupport jsonSupport,
            TradeActivitySupport tradeActivitySupport
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxSupport = outboxSupport;
        this.jsonSupport = jsonSupport;
        this.tradeActivitySupport = tradeActivitySupport;
    }

    @Transactional
    public void reserve(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "settlement-service")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "settlement-service", "EVENT_CONSUMED", "RUNNING",
                "Settlement Service consumed SettlementStartRequested", EventTypes.SETTLEMENT_START_REQUESTED, "fx-settlement-events");

        if (payload.simulateSettlementFailure()) {
            tradeActivitySupport.record(payload.tradeId(), "settlement-service", "SETTLEMENT_RESERVE", "FAILED",
                    "Simulated settlement reservation failure", EventTypes.SETTLEMENT_RESERVE_FAILED, "fx-settlement-events");
            enqueue(payload, EventTypes.SETTLEMENT_RESERVE_FAILED);
            return;
        }

        jdbcTemplate.update(
                "insert into settlement_record (settlement_record_id, trade_id, settlement_status, created_at, updated_at) values (?, ?, 'RESERVED', ?, ?)",
                "SET-" + UUID.randomUUID(),
                payload.tradeId(),
                now(),
                now()
        );
        tradeActivitySupport.record(payload.tradeId(), "settlement-service", "SETTLEMENT_RESERVE", "COMPLETED",
                "Settlement reserved", EventTypes.SETTLEMENT_RESERVED, "fx-settlement-events");
        enqueue(payload, EventTypes.SETTLEMENT_RESERVED);
    }

    @Transactional
    public void cancel(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "settlement-service-comp")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "settlement-service", "COMPENSATION_CONSUMED", "RUNNING",
                "Settlement Service consumed compensation request", EventTypes.CANCEL_SETTLEMENT_REQUESTED, "fx-compensation-events");

        jdbcTemplate.update(
                "update settlement_record set settlement_status = 'CANCELLED', updated_at = ? where trade_id = ?",
                now(),
                payload.tradeId()
        );
        tradeActivitySupport.record(payload.tradeId(), "settlement-service", "SETTLEMENT_CANCEL", "COMPENSATED",
                "Settlement cancelled", EventTypes.SETTLEMENT_CANCELLED, "fx-settlement-events");
        enqueue(payload, EventTypes.SETTLEMENT_CANCELLED);
    }

    private void enqueue(TradePayload payload, String eventType) {
        outboxSupport.enqueue(
                "settlement-service",
                "settlement_record",
                payload.tradeId(),
                eventType,
                "fx-settlement-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
