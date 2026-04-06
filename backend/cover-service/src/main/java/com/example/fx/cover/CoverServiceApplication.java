package com.example.fx.cover;

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

@SpringBootApplication(scanBasePackages = {"com.example.fx.cover", "com.example.fx.common"})
public class CoverServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CoverServiceApplication.class, args);
    }
}

@Component
class CoverConsumerRoute extends RouteBuilder {
    private final CoverTradeService coverTradeService;

    CoverConsumerRoute(CoverTradeService coverTradeService) {
        this.coverTradeService = coverTradeService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-trade-events", "cover-service"))
                .routeId("cover-consume-trade-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.TRADE_EXECUTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(coverTradeService, "book(${body}, ${header.eventId})")
                .end();

        from(KafkaConsumerUris.consumer("fx-compensation-events", "cover-service-comp"))
                .routeId("cover-consume-compensation-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.REVERSE_COVER_TRADE_REQUESTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(coverTradeService, "reverse(${body}, ${header.eventId})")
                .end();
    }
}

@Component
class CoverOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    CoverOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "cover-service", "coverOutboxPoller");
    }
}

@org.springframework.stereotype.Service
class CoverTradeService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;

    CoverTradeService(
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
    public void book(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "cover-service")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "cover-service", "EVENT_CONSUMED", "RUNNING",
                "Cover Service consumed TradeExecuted", EventTypes.TRADE_EXECUTED, "fx-trade-events");

        if (payload.simulateCoverFailure()) {
            tradeActivitySupport.record(payload.tradeId(), "cover-service", "COVER_BOOKING", "FAILED",
                    "Simulated cover booking failure", EventTypes.COVER_TRADE_FAILED, "fx-cover-events");
            outboxSupport.enqueue(
                    "cover-service",
                    "cover_trade",
                    payload.tradeId(),
                    EventTypes.COVER_TRADE_FAILED,
                    "fx-cover-events",
                    payload.tradeId(),
                    jsonSupport.write(payload),
                    payload.correlationId()
            );
            return;
        }

        jdbcTemplate.update(
                "insert into cover_trade (cover_trade_id, trade_id, cover_status, created_at, updated_at) values (?, ?, 'BOOKED', ?, ?)",
                "CVR-" + UUID.randomUUID(),
                payload.tradeId(),
                now(),
                now()
        );
        tradeActivitySupport.record(payload.tradeId(), "cover-service", "COVER_BOOKING", "COMPLETED",
                "Cover trade booked", EventTypes.COVER_TRADE_BOOKED, "fx-cover-events");

        outboxSupport.enqueue(
                "cover-service",
                "cover_trade",
                payload.tradeId(),
                EventTypes.COVER_TRADE_BOOKED,
                "fx-cover-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    @Transactional
    public void reverse(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "cover-service-comp")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "cover-service", "COMPENSATION_CONSUMED", "RUNNING",
                "Cover Service consumed compensation request", EventTypes.REVERSE_COVER_TRADE_REQUESTED, "fx-compensation-events");

        jdbcTemplate.update(
                "update cover_trade set cover_status = 'REVERSED', updated_at = ? where trade_id = ?",
                now(),
                payload.tradeId()
        );
        tradeActivitySupport.record(payload.tradeId(), "cover-service", "COVER_REVERSAL", "COMPENSATED",
                "Cover trade reversed", EventTypes.COVER_TRADE_REVERSED, "fx-cover-events");

        outboxSupport.enqueue(
                "cover-service",
                "cover_trade",
                payload.tradeId(),
                EventTypes.COVER_TRADE_REVERSED,
                "fx-cover-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
