package com.example.fx.compliance;

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

@SpringBootApplication(scanBasePackages = {"com.example.fx.compliance", "com.example.fx.common"})
public class ComplianceServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ComplianceServiceApplication.class, args);
    }
}

@Component
class ComplianceConsumerRoute extends RouteBuilder {
    private final ComplianceProcessingService complianceService;

    ComplianceConsumerRoute(ComplianceProcessingService complianceService) {
        this.complianceService = complianceService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-trade-events", "compliance-service"))
                .routeId("compliance-trade-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.TRADE_EXECUTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(complianceService, "check(${body}, ${header.eventId})")
                .end();
    }
}

@Component
class ComplianceOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    ComplianceOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "compliance-service", "complianceOutboxPoller");
    }
}

@org.springframework.stereotype.Service
class ComplianceProcessingService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;

    ComplianceProcessingService(
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
    public void check(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "compliance-service")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "compliance-service", "EVENT_CONSUMED", "RUNNING",
                "Compliance Service consumed TradeExecuted", EventTypes.TRADE_EXECUTED, "fx-trade-events");

        jdbcTemplate.update(
                "insert into compliance_record (compliance_id, trade_id, compliance_status, created_at) values (?, ?, ?, ?)",
                "CMP-" + UUID.randomUUID(),
                payload.tradeId(),
                payload.simulateComplianceFailure() ? "FAILED" : "CHECKED",
                now()
        );
        tradeActivitySupport.record(
                payload.tradeId(),
                "compliance-service",
                "COMPLIANCE_CHECK",
                payload.simulateComplianceFailure() ? "FAILED" : "COMPLETED",
                payload.simulateComplianceFailure() ? "Post-trade compliance failed" : "Post-trade compliance checked",
                payload.simulateComplianceFailure() ? EventTypes.COMPLIANCE_CHECK_FAILED : EventTypes.COMPLIANCE_CHECKED,
                "fx-compliance-events"
        );

        enqueue(
                payload,
                payload.simulateComplianceFailure() ? EventTypes.COMPLIANCE_CHECK_FAILED : EventTypes.COMPLIANCE_CHECKED
        );
    }

    private void enqueue(TradePayload payload, String eventType) {
        outboxSupport.enqueue(
                "compliance-service",
                "compliance_record",
                payload.tradeId(),
                eventType,
                "fx-compliance-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
