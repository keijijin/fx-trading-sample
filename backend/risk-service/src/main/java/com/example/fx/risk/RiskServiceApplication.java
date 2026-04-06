package com.example.fx.risk;

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

@SpringBootApplication(scanBasePackages = {"com.example.fx.risk", "com.example.fx.common"})
public class RiskServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskServiceApplication.class, args);
    }
}

@Component
class RiskConsumerRoute extends RouteBuilder {
    private final RiskProcessingService riskService;

    RiskConsumerRoute(RiskProcessingService riskService) {
        this.riskService = riskService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-cover-events", "risk-service"))
                .routeId("risk-cover-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.COVER_TRADE_BOOKED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(riskService, "updateRisk(${body}, ${header.eventId})")
                .end();

        from(KafkaConsumerUris.consumer("fx-compensation-events", "risk-service-comp"))
                .routeId("risk-comp-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.REVERSE_RISK_REQUESTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(riskService, "reverse(${body}, ${header.eventId})")
                .end();
    }
}

@Component
class RiskOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    RiskOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "risk-service", "riskOutboxPoller");
    }
}

@org.springframework.stereotype.Service
class RiskProcessingService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;

    RiskProcessingService(
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
    public void updateRisk(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "risk-service")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "risk-service", "EVENT_CONSUMED", "RUNNING",
                "Risk Service consumed CoverTradeBooked", EventTypes.COVER_TRADE_BOOKED, "fx-cover-events");

        if (payload.simulateRiskFailure()) {
            tradeActivitySupport.record(payload.tradeId(), "risk-service", "RISK_UPDATE", "FAILED",
                    "Simulated risk update failure", EventTypes.RISK_UPDATE_FAILED, "fx-risk-events");
            enqueue(payload, EventTypes.RISK_UPDATE_FAILED);
            return;
        }

        jdbcTemplate.update(
                "insert into risk_record (risk_record_id, trade_id, risk_status, created_at, updated_at) values (?, ?, 'APPLIED', ?, ?)",
                "RSK-" + UUID.randomUUID(),
                payload.tradeId(),
                now(),
                now()
        );
        tradeActivitySupport.record(payload.tradeId(), "risk-service", "RISK_UPDATE", "COMPLETED",
                "Risk record updated", EventTypes.RISK_UPDATED, "fx-risk-events");
        enqueue(payload, EventTypes.RISK_UPDATED);
    }

    @Transactional
    public void reverse(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "risk-service-comp")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "risk-service", "COMPENSATION_CONSUMED", "RUNNING",
                "Risk Service consumed compensation request", EventTypes.REVERSE_RISK_REQUESTED, "fx-compensation-events");

        jdbcTemplate.update(
                "update risk_record set risk_status = 'REVERSED', updated_at = ? where trade_id = ?",
                now(),
                payload.tradeId()
        );
        tradeActivitySupport.record(payload.tradeId(), "risk-service", "RISK_REVERSAL", "COMPENSATED",
                "Risk record reversed", EventTypes.RISK_REVERSED, "fx-risk-events");
        enqueue(payload, EventTypes.RISK_REVERSED);
    }

    private void enqueue(TradePayload payload, String eventType) {
        outboxSupport.enqueue(
                "risk-service",
                "risk_record",
                payload.tradeId(),
                eventType,
                "fx-risk-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
