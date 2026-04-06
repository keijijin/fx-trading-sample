package com.example.fx.notification;

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

@SpringBootApplication(scanBasePackages = {"com.example.fx.notification", "com.example.fx.common"})
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}

@Component
class NotificationConsumerRoute extends RouteBuilder {
    private final NotificationProcessingService notificationService;

    NotificationConsumerRoute(NotificationProcessingService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-trade-events", "notification-service"))
                .routeId("notification-trade-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.TRADE_EXECUTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(notificationService, "sendTradeNotice(${body}, ${header.eventId})")
                .end();

        from(KafkaConsumerUris.consumer("fx-compensation-events", "notification-service-comp"))
                .routeId("notification-comp-events")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.SEND_CORRECTION_NOTICE_REQUESTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(notificationService, "sendCorrectionNotice(${body}, ${header.eventId})")
                .end();
    }
}

@Component
class NotificationOutboxPublisherRoute extends AbstractOutboxPublisherRoute {
    NotificationOutboxPublisherRoute(OutboxSupport outboxSupport) {
        super(outboxSupport, "notification-service", "notificationOutboxPoller");
    }
}

@org.springframework.stereotype.Service
class NotificationProcessingService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;

    NotificationProcessingService(
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
    public void sendTradeNotice(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "notification-service")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "notification-service", "EVENT_CONSUMED", "RUNNING",
                "Notification Service consumed TradeExecuted", EventTypes.TRADE_EXECUTED, "fx-trade-events");

        jdbcTemplate.update(
                "insert into notification_record (notification_id, trade_id, notification_type, notification_status, created_at) values (?, ?, 'TRADE', ?, ?)",
                "NTF-" + UUID.randomUUID(),
                payload.tradeId(),
                payload.simulateNotificationFailure() ? "FAILED" : "SENT",
                now()
        );
        tradeActivitySupport.record(
                payload.tradeId(),
                "notification-service",
                "TRADE_NOTIFICATION",
                payload.simulateNotificationFailure() ? "FAILED" : "COMPLETED",
                payload.simulateNotificationFailure() ? "Trade notification failed" : "Trade notification sent",
                payload.simulateNotificationFailure() ? EventTypes.TRADE_NOTIFICATION_FAILED : EventTypes.TRADE_NOTIFICATION_SENT,
                "fx-notification-events"
        );

        enqueue(
                payload,
                payload.simulateNotificationFailure() ? EventTypes.TRADE_NOTIFICATION_FAILED : EventTypes.TRADE_NOTIFICATION_SENT
        );
    }

    @Transactional
    public void sendCorrectionNotice(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "notification-service-comp")) {
            return;
        }
        tradeActivitySupport.record(payload.tradeId(), "notification-service", "COMPENSATION_CONSUMED", "RUNNING",
                "Notification Service consumed correction notice request", EventTypes.SEND_CORRECTION_NOTICE_REQUESTED, "fx-compensation-events");

        jdbcTemplate.update(
                "insert into notification_record (notification_id, trade_id, notification_type, notification_status, created_at) values (?, ?, 'CORRECTION', 'SENT', ?)",
                "NTF-" + UUID.randomUUID(),
                payload.tradeId(),
                now()
        );
        tradeActivitySupport.record(payload.tradeId(), "notification-service", "CORRECTION_NOTICE", "COMPENSATED",
                "Correction notice sent", EventTypes.CORRECTION_NOTICE_SENT, "fx-notification-events");

        enqueue(payload, EventTypes.CORRECTION_NOTICE_SENT);
    }

    private void enqueue(TradePayload payload, String eventType) {
        outboxSupport.enqueue(
                "notification-service",
                "notification_record",
                payload.tradeId(),
                eventType,
                "fx-notification-events",
                payload.tradeId(),
                jsonSupport.write(payload),
                payload.correlationId()
        );
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }
}
