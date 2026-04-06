package com.example.fx.common.outbox;

import com.example.fx.common.activity.TradeActivitySupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxSupport {
    private final JdbcTemplate jdbcTemplate;
    private final TradeActivitySupport tradeActivitySupport;
    private final MeterRegistry meterRegistry;

    public OutboxSupport(
            JdbcTemplate jdbcTemplate,
            TradeActivitySupport tradeActivitySupport,
            MeterRegistry meterRegistry,
            @Value("${spring.application.name}") String applicationName
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.tradeActivitySupport = tradeActivitySupport;
        this.meterRegistry = meterRegistry;

        // Register gauges from a bean that is certainly instantiated in every service.
        Gauge.builder(
                        "fx_outbox_backlog_total",
                        jdbcTemplate,
                        jdbc -> count(jdbc,
                                "select count(*) from outbox_event where source_service = ? and status in ('NEW','RETRY','IN_PROGRESS')",
                                applicationName)
                )
                .tag("source_service", applicationName)
                .register(meterRegistry);

        Gauge.builder(
                        "fx_outbox_error_total",
                        jdbcTemplate,
                        jdbc -> count(jdbc,
                                "select count(*) from outbox_event where source_service = ? and status = 'ERROR'",
                                applicationName)
                )
                .tag("source_service", applicationName)
                .register(meterRegistry);
    }

    public boolean isDuplicate(String eventId, String consumerName) {
        try {
            jdbcTemplate.update(
                    "insert into processed_message (consumer_name, event_id, processed_at) values (?, ?, ?)",
                    consumerName,
                    eventId,
                    Timestamp.from(Instant.now())
            );
            return false;
        } catch (DuplicateKeyException exception) {
            return true;
        }
    }

    public void enqueue(
            String sourceService,
            String aggregateType,
            String aggregateId,
            String eventType,
            String topicName,
            String messageKey,
            String payload,
            String correlationId
    ) {
        jdbcTemplate.update(
                "insert into outbox_event (" +
                        "event_id, aggregate_type, aggregate_id, event_type, topic_name, message_key, payload, status, " +
                        "retry_count, next_retry_at, correlation_id, source_service, created_at, sent_at, last_error_message, version_no" +
                        ") values (?, ?, ?, ?, ?, ?, ?, 'NEW', 0, null, ?, ?, ?, null, null, 0)",
                UUID.randomUUID().toString(),
                aggregateType,
                aggregateId,
                eventType,
                topicName,
                messageKey,
                payload,
                correlationId,
                sourceService,
                Timestamp.from(Instant.now())
        );
        tradeActivitySupport.record(
                aggregateId,
                sourceService,
                "OUTBOX_ENQUEUED",
                "NEW",
                "Outbox event registered for asynchronous delivery",
                eventType,
                topicName
        );
        counter("fx_outbox_enqueued_total", sourceService, eventType).increment();
    }

    /**
     * UPDATE ... RETURNING で claim と行取得を 1 往復で行う。
     * 該当行がなければ null（既に他ポーラーが claim 済み）。
     */
    public Map<String, Object> claimAndLoad(String eventId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "update outbox_event set status = 'IN_PROGRESS', version_no = version_no + 1 " +
                        "where event_id = ? and status in ('NEW', 'RETRY') returning *",
                eventId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Kafka 送信成功。コンテキストはルート側のヘッダから渡され、findById を呼ばない。
     */
    public void markSent(String eventId, String aggregateId, String sourceService, String eventType, String topicName) {
        jdbcTemplate.update(
                "update outbox_event set status = 'SENT', sent_at = ?, version_no = version_no + 1 where event_id = ?",
                Timestamp.from(Instant.now()),
                eventId
        );
        tradeActivitySupport.record(aggregateId, sourceService, "OUTBOX_SENT", "SENT",
                "Outbox event published to Kafka", eventType, topicName);
        counter("fx_outbox_sent_total", sourceService, eventType).increment();
    }

    /**
     * Kafka 送信失敗。コンテキストはルート側のヘッダから渡される。
     */
    public void markFailed(String eventId, String errorMessage, String aggregateId, String sourceService, String eventType, String topicName) {
        jdbcTemplate.update(
                "update outbox_event " +
                        "set retry_count = retry_count + 1, " +
                        "status = case when retry_count + 1 >= 5 then 'ERROR' else 'RETRY' end, " +
                        "next_retry_at = case when retry_count + 1 >= 5 then null else ? end, " +
                        "last_error_message = ?, version_no = version_no + 1 " +
                        "where event_id = ?",
                Timestamp.from(Instant.now().plusSeconds(5)),
                truncate(errorMessage),
                eventId
        );
        tradeActivitySupport.record(aggregateId, sourceService, "OUTBOX_FAILED", "RETRY",
                truncate(errorMessage == null ? "Unknown publish error" : errorMessage), eventType, topicName);
        counter("fx_outbox_failed_total", sourceService, eventType).increment();
    }

    public int cleanupSent(int maxAge, int limit) {
        return jdbcTemplate.update(
                "delete from outbox_event where event_id in (" +
                        "select event_id from outbox_event where status = 'SENT' and sent_at < ? " +
                        "order by sent_at fetch first ? rows only)",
                Timestamp.from(Instant.now().minusSeconds(maxAge)),
                limit
        );
    }

    private double count(JdbcTemplate jdbcTemplate, String sql, String sourceService) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, sourceService);
        return count == null ? 0.0d : count.doubleValue();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 1024 ? value.substring(0, 1024) : value;
    }

    private Counter counter(String name, String sourceService, String eventType) {
        return Counter.builder(name)
                .tag("source_service", sourceService == null ? "unknown" : sourceService)
                .tag("event_type", eventType == null ? "unknown" : eventType)
                .register(meterRegistry);
    }
}
