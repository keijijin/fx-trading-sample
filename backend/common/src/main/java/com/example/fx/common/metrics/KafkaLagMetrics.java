package com.example.fx.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class KafkaLagMetrics {
    private final String bootstrapServers;
    private final Map<String, AtomicLong> lagByGroupTopic = new ConcurrentHashMap<>();
    private final List<GroupTopic> groupTopics;

    public KafkaLagMetrics(
            MeterRegistry meterRegistry,
            @Value("${kafka.bootstrap.servers}") String bootstrapServers,
            @Value("${spring.application.name}") String applicationName
    ) {
        this.bootstrapServers = bootstrapServers;
        this.groupTopics = mapping(applicationName);

        for (GroupTopic groupTopic : groupTopics) {
            AtomicLong holder = new AtomicLong(0L);
            lagByGroupTopic.put(key(groupTopic.consumerGroup(), groupTopic.topic()), holder);
            Gauge.builder("fx_kafka_consumer_group_lag", holder, AtomicLong::doubleValue)
                    .tag("consumer_group", groupTopic.consumerGroup())
                    .tag("topic", groupTopic.topic())
                    .register(meterRegistry);
        }
    }

    @Scheduled(fixedDelayString = "${observability.kafka.lag.poll.ms:15000}")
    public void poll() {
        if (groupTopics.isEmpty()) {
            return;
        }

        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        Properties consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", bootstrapServers);
        consumerProps.put("group.id", "lag-probe-" + System.nanoTime());
        consumerProps.put("key.deserializer", StringDeserializer.class.getName());
        consumerProps.put("value.deserializer", StringDeserializer.class.getName());
        consumerProps.put("enable.auto.commit", "false");
        consumerProps.put("auto.offset.reset", "latest");

        try (AdminClient adminClient = AdminClient.create(adminProps);
             KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
            for (GroupTopic groupTopic : groupTopics) {
                ListConsumerGroupOffsetsResult offsetsResult =
                        adminClient.listConsumerGroupOffsets(groupTopic.consumerGroup());
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> groupOffsets =
                        offsetsResult.partitionsToOffsetAndMetadata().get();

                List<TopicPartition> partitions = new ArrayList<>();
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> filteredOffsets = new ConcurrentHashMap<>();
                for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> entry : groupOffsets.entrySet()) {
                    if (entry.getKey().topic().equals(groupTopic.topic())) {
                        partitions.add(entry.getKey());
                        filteredOffsets.put(entry.getKey(), entry.getValue());
                    }
                }

                if (partitions.isEmpty()) {
                    lagByGroupTopic.get(key(groupTopic.consumerGroup(), groupTopic.topic())).set(0L);
                    continue;
                }

                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions, Duration.ofSeconds(5));
                long lag = 0L;
                for (TopicPartition partition : partitions) {
                    long current = filteredOffsets.get(partition).offset();
                    long end = endOffsets.getOrDefault(partition, current);
                    lag += Math.max(end - current, 0L);
                }
                lagByGroupTopic.get(key(groupTopic.consumerGroup(), groupTopic.topic())).set(lag);
            }
        } catch (Exception ignored) {
            // Keep last observed value on polling failures.
        }
    }

    private List<GroupTopic> mapping(String applicationName) {
        return switch (applicationName) {
            case "trade-saga-service" -> List.of(
                    new GroupTopic("trade-saga-service-seed", "fx-trade-events"),
                    new GroupTopic("trade-saga-fx-cover-events", "fx-cover-events"),
                    new GroupTopic("trade-saga-fx-risk-events", "fx-risk-events"),
                    new GroupTopic("trade-saga-fx-accounting-events", "fx-accounting-events"),
                    new GroupTopic("trade-saga-fx-settlement-events", "fx-settlement-events"),
                    new GroupTopic("trade-saga-fx-notification-events", "fx-notification-events"),
                    new GroupTopic("trade-saga-fx-compliance-events", "fx-compliance-events")
            );
            case "cover-service" -> List.of(
                    new GroupTopic("cover-service", "fx-trade-events"),
                    new GroupTopic("cover-service-comp", "fx-compensation-events")
            );
            case "risk-service" -> List.of(
                    new GroupTopic("risk-service", "fx-cover-events"),
                    new GroupTopic("risk-service-comp", "fx-compensation-events")
            );
            case "accounting-service" -> List.of(
                    new GroupTopic("accounting-service", "fx-cover-events"),
                    new GroupTopic("accounting-service-comp", "fx-compensation-events")
            );
            case "settlement-service" -> List.of(
                    new GroupTopic("settlement-service", "fx-settlement-events"),
                    new GroupTopic("settlement-service-comp", "fx-compensation-events")
            );
            case "notification-service" -> List.of(
                    new GroupTopic("notification-service", "fx-trade-events"),
                    new GroupTopic("notification-service-comp", "fx-compensation-events")
            );
            case "compliance-service" -> List.of(
                    new GroupTopic("compliance-service", "fx-trade-events")
            );
            default -> List.of();
        };
    }

    private String key(String group, String topic) {
        return group + "::" + topic;
    }

    private record GroupTopic(String consumerGroup, String topic) {
    }
}
