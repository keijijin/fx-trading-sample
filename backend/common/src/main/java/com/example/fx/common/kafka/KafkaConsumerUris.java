package com.example.fx.common.kafka;

/**
 * Shared Kafka consumer endpoint tuning to reduce rebalance churn and poll timeouts.
 */
public final class KafkaConsumerUris {

    private KafkaConsumerUris() {
    }

    /**
     * Consumer URI for Camel kafka component. Cooperative assignor is set via
     * {@code camel.component.kafka.additional-properties} in application.yml.
     */
    public static String consumer(String topic, String groupId) {
        return "kafka:" + topic
                + "?brokers={{kafka.bootstrap.servers}}"
                + "&groupId=" + groupId
                + "&consumersCount={{kafka.consumers.count:1}}"
                + "&maxPollRecords=100"
                + "&pollTimeoutMs=1000"
                + "&sessionTimeoutMs=60000"
                + "&heartbeatIntervalMs=20000"
                + "&maxPollIntervalMs=600000";
    }
}
