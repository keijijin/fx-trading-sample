package com.example.fx.accounting;

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

@SpringBootApplication(scanBasePackages = {"com.example.fx.accounting", "com.example.fx.common"})
public class AccountingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountingServiceApplication.class, args);
    }
}

@Component
class AccountingConsumerRoute extends RouteBuilder {
    private final AccountingProcessingService accountingService;

    AccountingConsumerRoute(AccountingProcessingService accountingService) {
        this.accountingService = accountingService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-cover-events", "accounting-service"))
                .routeId("accounting-cover-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.COVER_TRADE_BOOKED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(accountingService, "post(${body}, ${header.eventId})")
                .end();

        from(KafkaConsumerUris.consumer("fx-compensation-events", "accounting-service-comp"))
                .routeId("accounting-comp-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.REVERSE_ACCOUNTING_REQUESTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(accountingService, "reverse(${body}, ${header.eventId})")
                .end();
    }
}

@Component
class AccountingOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    AccountingOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "accounting-service", "accountingOutboxPoller");
    }
}

@org.springframework.stereotype.Service
class AccountingProcessingService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;

    AccountingProcessingService(
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
    public void post(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "accounting-service")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "accounting-service", "EVENT_CONSUMED", "RUNNING",
                "Accounting Service consumed CoverTradeBooked", EventTypes.COVER_TRADE_BOOKED, "fx-cover-events");

        if (payload.simulateAccountingFailure()) {
            tradeActivitySupport.record(payload.tradeId(), "accounting-service", "ACCOUNTING_POST", "FAILED",
                    "Simulated accounting posting failure", EventTypes.ACCOUNTING_POST_FAILED, "fx-accounting-events");
            enqueue(payload, EventTypes.ACCOUNTING_POST_FAILED);
            return;
        }

        jdbcTemplate.update(
                "insert into accounting_entry (accounting_entry_id, trade_id, accounting_status, created_at, updated_at) values (?, ?, 'POSTED', ?, ?)",
                "ACC-" + UUID.randomUUID(),
                payload.tradeId(),
                now(),
                now()
        );
        tradeActivitySupport.record(payload.tradeId(), "accounting-service", "ACCOUNTING_POST", "COMPLETED",
                "Accounting entry posted", EventTypes.ACCOUNTING_POSTED, "fx-accounting-events");
        enqueue(payload, EventTypes.ACCOUNTING_POSTED);
    }

    @Transactional
    public void reverse(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "accounting-service-comp")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "accounting-service", "COMPENSATION_CONSUMED", "RUNNING",
                "Accounting Service consumed compensation request", EventTypes.REVERSE_ACCOUNTING_REQUESTED, "fx-compensation-events");

        jdbcTemplate.update(
                "update accounting_entry set accounting_status = 'REVERSED', updated_at = ? where trade_id = ?",
                now(),
                payload.tradeId()
        );
        tradeActivitySupport.record(payload.tradeId(), "accounting-service", "ACCOUNTING_REVERSAL", "COMPENSATED",
                "Accounting entry reversed", EventTypes.ACCOUNTING_REVERSED, "fx-accounting-events");
        enqueue(payload, EventTypes.ACCOUNTING_REVERSED);
    }

    private void enqueue(TradePayload payload, String eventType) {
        outboxSupport.enqueue(
                "accounting-service",
                "accounting_entry",
                payload.tradeId(),
                eventType,
                "fx-accounting-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
