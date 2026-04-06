package com.example.fx.common.activity;

import com.example.fx.common.db.ActivityJdbcSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TradeActivitySupport {
    private final org.springframework.jdbc.core.JdbcOperations jdbcOperations;
    private final Queue<ActivityRecord> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger(0);
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private final int maxBatchSize;
    private final int maxQueueSize;
    private final Counter enqueuedCounter;
    private final Counter droppedCounter;
    private final Counter persistedCounter;
    private final Counter failedCounter;

    public TradeActivitySupport(
            ActivityJdbcSupport activityJdbcSupport,
            MeterRegistry meterRegistry,
            @Value("${spring.application.name}") String applicationName,
            @Value("${activity.queue.batch-size:200}") int maxBatchSize,
            @Value("${activity.queue.max-size:10000}") int maxQueueSize
    ) {
        this.jdbcOperations = activityJdbcSupport.jdbcOperations();
        this.maxBatchSize = maxBatchSize;
        this.maxQueueSize = maxQueueSize;
        this.enqueuedCounter = Counter.builder("fx_trade_activity_enqueued_total")
                .tag("application", applicationName)
                .register(meterRegistry);
        this.droppedCounter = Counter.builder("fx_trade_activity_dropped_total")
                .tag("application", applicationName)
                .register(meterRegistry);
        this.persistedCounter = Counter.builder("fx_trade_activity_persisted_total")
                .tag("application", applicationName)
                .register(meterRegistry);
        this.failedCounter = Counter.builder("fx_trade_activity_flush_failed_total")
                .tag("application", applicationName)
                .register(meterRegistry);

        Gauge.builder("fx_trade_activity_queue_size", queueSize, AtomicInteger::doubleValue)
                .tag("application", applicationName)
                .register(meterRegistry);
    }

    public void record(
            String tradeId,
            String serviceName,
            String activityType,
            String status,
            String detail,
            String eventType,
            String topicName
    ) {
        int nextSize = queueSize.incrementAndGet();
        if (nextSize > maxQueueSize) {
            queueSize.decrementAndGet();
            droppedCounter.increment();
            return;
        }

        queue.add(new ActivityRecord(
                UUID.randomUUID().toString(),
                tradeId,
                serviceName,
                activityType,
                status,
                detail,
                eventType,
                topicName,
                Timestamp.from(Instant.now())
        ));
        enqueuedCounter.increment();
    }

    @Scheduled(fixedDelayString = "${activity.queue.flush.ms:250}")
    public void flush() {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }

        try {
            flushBatch();
        } finally {
            flushing.set(false);
        }
    }

    @PreDestroy
    public void drain() {
        while (queueSize.get() > 0) {
            flushBatch();
        }
    }

    private void flushBatch() {
        List<ActivityRecord> batch = new ArrayList<>(maxBatchSize);
        while (batch.size() < maxBatchSize) {
            ActivityRecord record = queue.poll();
            if (record == null) {
                break;
            }
            queueSize.decrementAndGet();
            batch.add(record);
        }

        if (batch.isEmpty()) {
            return;
        }

        try {
            jdbcOperations.batchUpdate(
                "insert into trade_activity (" +
                        "activity_id, trade_id, service_name, activity_type, activity_status, detail, event_type, topic_name, created_at" +
                        ") values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    batch,
                    batch.size(),
                    (ps, record) -> {
                        ps.setString(1, record.activityId());
                        ps.setString(2, record.tradeId());
                        ps.setString(3, record.serviceName());
                        ps.setString(4, record.activityType());
                        ps.setString(5, record.status());
                        ps.setString(6, record.detail());
                        ps.setString(7, record.eventType());
                        ps.setString(8, record.topicName());
                        ps.setTimestamp(9, record.createdAt());
                    }
            );
            persistedCounter.increment(batch.size());
        } catch (Exception exception) {
            failedCounter.increment();
            for (ActivityRecord record : batch) {
                queue.add(record);
                queueSize.incrementAndGet();
            }
        }
    }

    private record ActivityRecord(
            String activityId,
            String tradeId,
            String serviceName,
            String activityType,
            String status,
            String detail,
            String eventType,
            String topicName,
            Timestamp createdAt
    ) {
    }
}
