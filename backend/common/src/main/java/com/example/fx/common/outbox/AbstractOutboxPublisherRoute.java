package com.example.fx.common.outbox;

import org.apache.camel.builder.RouteBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public abstract class AbstractOutboxPublisherRoute extends RouteBuilder {
    private final OutboxSupport outboxSupport;
    private final String sourceService;
    private final String pollerName;

    protected AbstractOutboxPublisherRoute(OutboxSupport outboxSupport, String sourceService, String pollerName) {
        this.outboxSupport = outboxSupport;
        this.sourceService = sourceService;
        this.pollerName = pollerName;
    }

    @Override
    public void configure() {
        from("timer:" + pollerName + "Cleanup?fixedRate=true&period={{outbox.cleanup.period.ms:60000}}")
                .routeId(sourceService + "-outbox-cleanup")
                .bean(outboxSupport, "cleanupSent(300, 500)")
                .filter(body().isGreaterThan(0))
                    .log("${body} sent outbox events cleaned up")
                .end();

        from("timer:" + pollerName + "?fixedRate=true&period={{outbox.poll.period.ms:100}}")
                .routeId(sourceService + "-outbox-poller")
                .to("sql:select event_id from outbox_event where status in ('NEW','RETRY') " +
                        "and source_service = '" + sourceService + "' " +
                        "and (next_retry_at is null or next_retry_at <= current_timestamp) " +
                        "order by created_at fetch first {{outbox.max.rows:100}} rows only" +
                        "?dataSource=#dataSource&outputType=SelectList")
                .split(body()).parallelProcessing().executorService(outboxPool())
                    .to("direct:" + sourceService + "-publishOutboxEvent")
                .end();

        from("direct:" + sourceService + "-publishOutboxEvent")
                .routeId(sourceService + "-publish-outbox-event")
                .setHeader("eventId", simple("${body[event_id]}"))
                .bean(outboxSupport, "claimAndLoad(${header.eventId})")
                .filter(body().isNotNull())
                    .setHeader("kafka.KEY", simple("${body[message_key]}"))
                    .setHeader("eventType", simple("${body[event_type]}"))
                    .setHeader("topicName", simple("${body[topic_name]}"))
                    .setHeader("aggregateId", simple("${body[aggregate_id]}"))
                    .setHeader("sourceService", simple("${body[source_service]}"))
                    .setBody(simple("${body[payload]}"))
                    .doTry()
                        .toD("kafka:${header.topicName}?brokers={{kafka.bootstrap.servers}}&requestRequiredAcks=all&lingerMs=5")
                        .bean(outboxSupport, "markSent(${header.eventId}, ${header.aggregateId}, ${header.sourceService}, ${header.eventType}, ${header.topicName})")
                    .doCatch(Exception.class)
                        .bean(outboxSupport, "markFailed(${header.eventId}, ${exception.message}, ${header.aggregateId}, ${header.sourceService}, ${header.eventType}, ${header.topicName})")
                    .end()
                .end();
    }

    private ExecutorService outboxPool() {
        return Executors.newFixedThreadPool(4);
    }
}
