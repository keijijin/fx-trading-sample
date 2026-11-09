package com.example.fx.integration;

import com.example.fx.accounting.AccountingServiceApplication;
import com.example.fx.common.event.EventTypes;
import com.example.fx.compliance.ComplianceServiceApplication;
import com.example.fx.cover.CoverServiceApplication;
import com.example.fx.core.FxCoreServiceApplication;
import com.example.fx.notification.NotificationServiceApplication;
import com.example.fx.risk.RiskServiceApplication;
import com.example.fx.saga.TradeSagaServiceApplication;
import com.example.fx.settlement.SettlementServiceApplication;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class TradeFlowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
    static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    static int FX_CORE_PORT;
    static int TRADE_SAGA_PORT;
    static int COVER_PORT;
    static int RISK_PORT;
    static int ACCOUNTING_PORT;
    static int SETTLEMENT_PORT;
    static int NOTIFICATION_PORT;
    static int COMPLIANCE_PORT;

    @BeforeAll
    static void startSystem() throws Exception {
        POSTGRES.start();
        KAFKA.start();
        initSchema();

        FX_CORE_PORT = availablePort();
        TRADE_SAGA_PORT = availablePort();
        COVER_PORT = availablePort();
        RISK_PORT = availablePort();
        ACCOUNTING_PORT = availablePort();
        SETTLEMENT_PORT = availablePort();
        NOTIFICATION_PORT = availablePort();
        COMPLIANCE_PORT = availablePort();

        CONTEXTS.add(startApp(FxCoreServiceApplication.class, FX_CORE_PORT));
        CONTEXTS.add(startApp(TradeSagaServiceApplication.class, TRADE_SAGA_PORT));
        CONTEXTS.add(startApp(CoverServiceApplication.class, COVER_PORT));
        CONTEXTS.add(startApp(RiskServiceApplication.class, RISK_PORT));
        CONTEXTS.add(startApp(AccountingServiceApplication.class, ACCOUNTING_PORT));
        CONTEXTS.add(startApp(SettlementServiceApplication.class, SETTLEMENT_PORT));
        CONTEXTS.add(startApp(NotificationServiceApplication.class, NOTIFICATION_PORT));
        CONTEXTS.add(startApp(ComplianceServiceApplication.class, COMPLIANCE_PORT));

        Thread.sleep(4000L);
    }

    @AfterAll
    static void stopSystem() {
        for (int index = CONTEXTS.size() - 1; index >= 0; index--) {
            CONTEXTS.get(index).close();
        }
        KAFKA.stop();
        POSTGRES.stop();
    }

    @Test
    void tradeExecutedShouldCompleteSaga() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-001"),
                Map.entry("currencyPair", "USD/JPY"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1000.00),
                Map.entry("requestedPrice", 150.25),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));

        String tradeId = response.get("tradeId").toString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("COMPLETED", saga.get("saga_status"));
                    assertEquals("COMPLETED", saga.get("cover_status"));
                    assertEquals("COMPLETED", saga.get("risk_status"));
                    assertEquals("COMPLETED", saga.get("accounting_status"));
                    assertEquals("COMPLETED", saga.get("settlement_status"));
                    assertEquals("COMPLETED", saga.get("notification_status"));
                    assertEquals("COMPLETED", saga.get("compliance_status"));
                });
    }

    @Test
    void coverTradeFailedShouldTriggerCompensation() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-002"),
                Map.entry("currencyPair", "EUR/USD"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1500.00),
                Map.entry("requestedPrice", 1.09),
                Map.entry("simulateCoverFailure", true),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));

        String tradeId = response.get("tradeId").toString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("CANCELLED", saga.get("saga_status"));
                    assertEquals("FAILED", saga.get("cover_status"));
                    assertEquals("NOT_STARTED", saga.get("risk_status"));
                    assertEquals("NOT_STARTED", saga.get("accounting_status"));
                    assertEquals("NOT_STARTED", saga.get("settlement_status"));
                    assertEquals("COMPENSATED", saga.get("notification_status"));
                });
    }

    @Test
    void duplicateOrderIdShouldBeRejected() throws Exception {
        String duplicateOrderId = "ORD-DUP-001";
        Map<String, Object> body = Map.ofEntries(
                Map.entry("orderId", duplicateOrderId),
                Map.entry("accountId", "ACC-003"),
                Map.entry("currencyPair", "USD/JPY"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1000.00),
                Map.entry("requestedPrice", 150.25),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        );

        postTrade(body);
        postTradeExpectingStatus(body, 409);
    }

    @Test
    void accountingFailureShouldStartCompensationAndSuppressSettlement() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-004"),
                Map.entry("currencyPair", "USD/JPY"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1000.00),
                Map.entry("requestedPrice", 150.25),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", true),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));

        String tradeId = response.get("tradeId").toString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertTrue(Set.of("COMPENSATING", "CANCELLED", "FAILED").contains(saga.get("saga_status")), saga.toString());
                    assertTrue(Set.of("COMPLETED", "COMPENSATED").contains(saga.get("cover_status")), saga.toString());
                    assertTrue(Set.of("PENDING", "COMPLETED", "COMPENSATED", "FAILED").contains(saga.get("risk_status")), saga.toString());
                    assertEquals("FAILED", saga.get("accounting_status"));
                    assertEquals("NOT_STARTED", saga.get("settlement_status"));
                    assertTrue(Set.of("NOT_STARTED", "COMPENSATED", "COMPLETED").contains(saga.get("notification_status")), saga.toString());
                });

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("CANCELLED", saga.get("saga_status"), saga.toString());
                });
    }

    @Test
    void notificationFailureShouldNotRollbackTrade() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-005"),
                Map.entry("currencyPair", "USD/JPY"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1000.00),
                Map.entry("requestedPrice", 150.25),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", true),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));

        String tradeId = response.get("tradeId").toString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("COMPLETED", saga.get("saga_status"));
                    assertEquals("COMPLETED", saga.get("cover_status"));
                    assertEquals("COMPLETED", saga.get("risk_status"));
                    assertEquals("COMPLETED", saga.get("accounting_status"));
                    assertEquals("COMPLETED", saga.get("settlement_status"));
                    assertEquals("FAILED", saga.get("notification_status"));
                    assertEquals("COMPLETED", saga.get("compliance_status"));
                });
    }

    @Test
    void e2eStatusEndpointShouldBeServedFromProjection() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-006"),
                Map.entry("currencyPair", "USD/JPY"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1000.00),
                Map.entry("requestedPrice", 150.25),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));

        String tradeId = response.get("tradeId").toString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> projection = loadProjection(tradeId);
                    assertNotNull(projection, "Wait for the asynchronous projection to be created");
                    assertEquals("COMPLETED", projection.get("saga_status"));
                    assertEquals("COMPLETED", projection.get("notification_status"));
                });

        HttpResponse<String> endpointResponse = get("/api/trades/" + tradeId + "/e2e-status");
        Map<String, Object> payload = OBJECT_MAPPER.readValue(endpointResponse.body(), new TypeReference<>() {
        });
        assertEquals("COMPLETED", payload.get("sagaStatus"));
        assertEquals("COMPLETED", payload.get("notificationStatus"));
    }

    @Test
    void traceEndpointShouldReturnProjectedSummary() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-007"),
                Map.entry("currencyPair", "EUR/USD"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1200.00),
                Map.entry("requestedPrice", 1.09),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));

        String tradeId = response.get("tradeId").toString();

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> assertNotNull(loadProjection(tradeId)));

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    HttpResponse<String> endpointResponse = get("/api/trades/" + tradeId + "/trace");
                    Map<String, Object> payload = OBJECT_MAPPER.readValue(endpointResponse.body(), new TypeReference<>() {
                    });
                    assertEquals(tradeId, payload.get("tradeId"));
                    assertEquals("COMPLETED", payload.get("sagaStatus"));
                    assertEquals("EXECUTED", payload.get("tradeStatus"));
                    assertNotNull(payload.get("services"));
                });
    }

    /** Each saga step has its own topic and consumer, so CoverTradeBooked can arrive last. */
    @Test
    void lateCoverEventShouldNotRegressCompletedStepsAndShouldCompleteSaga() throws Exception {
        String tradeId = "TRD-LATE-COVER-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "tradeId", tradeId,
                "orderId", "ORD-" + tradeId,
                "accountId", "ACC-009",
                "currencyPair", "USD/JPY",
                "side", "BUY",
                "orderAmount", 1000.00,
                "executedAmount", 1000.00,
                "executedPrice", 150.25,
                "correlationId", "CORR-" + tradeId
        );

        publishEvent("fx-risk-events", "RiskUpdated", tradeId, payload);
        publishEvent("fx-accounting-events", "AccountingPosted", tradeId, payload);
        publishEvent("fx-compliance-events", "ComplianceChecked", tradeId, payload);
        publishEvent("fx-notification-events", "TradeNotificationSent", tradeId, payload);
        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("COMPLETED", saga.get("risk_status"));
                    assertEquals("COMPLETED", saga.get("accounting_status"));
                    assertEquals("COMPLETED", saga.get("settlement_status"));
                    assertEquals("COMPLETED", saga.get("compliance_status"));
                    assertEquals("COMPLETED", saga.get("notification_status"));
                });

        publishEvent("fx-cover-events", "CoverTradeBooked", tradeId, payload);

        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("COMPLETED", saga.get("cover_status"), saga.toString());
                    assertEquals("COMPLETED", saga.get("risk_status"), saga.toString());
                    assertEquals("COMPLETED", saga.get("accounting_status"), saga.toString());
                    assertEquals("COMPLETED", saga.get("saga_status"), saga.toString());
                });
    }

    /** Steps that finish after compensation started must still be reversed before the saga closes. */
    @Test
    void coverArrivingAfterCompensationStartedShouldBeReversed() throws Exception {
        String tradeId = "TRD-COMP-LATE-" + UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "tradeId", tradeId,
                "orderId", "ORD-" + tradeId,
                "accountId", "ACC-010",
                "currencyPair", "USD/JPY",
                "side", "BUY",
                "orderAmount", 1000.00,
                "executedAmount", 1000.00,
                "executedPrice", 150.25,
                "correlationId", "CORR-" + tradeId
        );

        publishEvent("fx-compliance-events", EventTypes.COMPLIANCE_CHECK_FAILED, tradeId, payload);
        Awaitility.await()
                .atMost(Duration.ofSeconds(45))
                .pollInterval(Duration.ofMillis(250))
                .ignoreExceptions()
                .untilAsserted(() -> assertEquals("COMPENSATING", loadSaga(tradeId).get("saga_status")));

        // The real risk and accounting services also consume this event, as they would in production.
        publishEvent("fx-cover-events", EventTypes.COVER_TRADE_BOOKED, tradeId, payload);

        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> {
                    Map<String, String> saga = loadSaga(tradeId);
                    assertEquals("COMPENSATED", saga.get("cover_status"), saga.toString());
                    assertEquals("COMPENSATED", saga.get("risk_status"), saga.toString());
                    assertEquals("COMPENSATED", saga.get("accounting_status"), saga.toString());
                    assertEquals("NOT_STARTED", saga.get("settlement_status"), saga.toString());
                    assertEquals("CANCELLED", saga.get("saga_status"), saga.toString());
                });
    }

    private static void publishEvent(String topic, String eventType, String tradeId, Map<String, Object> payload) throws Exception {
        Map<String, Object> producerConfig = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerConfig)) {
            ProducerRecord<String, String> kafkaRecord =
                    new ProducerRecord<>(topic, tradeId, OBJECT_MAPPER.writeValueAsString(payload));
            kafkaRecord.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
            kafkaRecord.headers().add("eventId", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            producer.send(kafkaRecord).get();
        }
    }

    /** Per-trade ordering depends on the record key; covers fx-core's and the shared Outbox publisher. */
    @Test
    void outboxEventsShouldUseTradeIdAsKafkaKey() throws Exception {
        Map<String, Object> response = postTrade(Map.ofEntries(
                Map.entry("accountId", "ACC-008"),
                Map.entry("currencyPair", "USD/JPY"),
                Map.entry("side", "BUY"),
                Map.entry("orderAmount", 1000.00),
                Map.entry("requestedPrice", 150.25),
                Map.entry("simulateCoverFailure", false),
                Map.entry("simulateRiskFailure", false),
                Map.entry("simulateAccountingFailure", false),
                Map.entry("simulateSettlementFailure", false),
                Map.entry("simulateNotificationFailure", false),
                Map.entry("simulateComplianceFailure", false),
                Map.entry("preTradeComplianceFailure", false)
        ));
        String tradeId = response.get("tradeId").toString();

        Map<String, Object> consumerConfig = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "record-key-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        Map<String, String> keysByTopic = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfig)) {
            consumer.subscribe(List.of("fx-trade-events", "fx-cover-events"));
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (keysByTopic.size() < 2 && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> kafkaRecord : consumer.poll(Duration.ofMillis(250))) {
                    if (kafkaRecord.value().contains(tradeId)) {
                        keysByTopic.put(kafkaRecord.topic(), kafkaRecord.key());
                    }
                }
            }
        }

        assertEquals(Map.of("fx-trade-events", tradeId, "fx-cover-events", tradeId), keysByTopic);
    }

    private static ConfigurableApplicationContext startApp(Class<?> appClass, int port) {
        String host = POSTGRES.getHost();
        String dbPort = Integer.toString(POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        String dbName = POSTGRES.getDatabaseName();
        String dbUser = POSTGRES.getUsername();
        String dbPassword = POSTGRES.getPassword();
        String jdbcUrl = POSTGRES.getJdbcUrl();

        return new SpringApplicationBuilder(appClass)
                .properties(Map.ofEntries(
                        Map.entry("server.port", Integer.toString(port)),
                        Map.entry("SERVER_PORT", Integer.toString(port)),
                        Map.entry("spring.datasource.url", jdbcUrl),
                        Map.entry("spring.datasource.username", dbUser),
                        Map.entry("spring.datasource.password", dbPassword),
                        Map.entry("DB_HOST", host),
                        Map.entry("DB_PORT", dbPort),
                        Map.entry("DB_NAME", dbName),
                        Map.entry("DB_USER", dbUser),
                        Map.entry("DB_PASSWORD", dbPassword),
                        Map.entry("kafka.bootstrap.servers", KAFKA.getBootstrapServers()),
                        Map.entry("KAFKA_BOOTSTRAP_SERVERS", KAFKA.getBootstrapServers()),
                        Map.entry("outbox.poll.period.ms", "50"),
                        Map.entry("OUTBOX_POLL_PERIOD_MS", "50"),
                        Map.entry("outbox.max.rows", "100"),
                        Map.entry("OUTBOX_MAX_ROWS", "100")
                ))
                .run();
    }

    private static int availablePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void initSchema() throws Exception {
        String sql = Files.readString(Path.of("../db/init.sql").normalize());
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Map<String, Object> postTrade(Map<String, Object> body) throws Exception {
        HttpResponse<String> response = postTradeExpectingStatus(body, 201);
        return OBJECT_MAPPER.readValue(response.body(), new TypeReference<>() {
        });
    }

    private static HttpResponse<String> postTradeExpectingStatus(Map<String, Object> body, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + FX_CORE_PORT + "/api/trades"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return response;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + FX_CORE_PORT + path))
                .GET()
                .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private static Map<String, String> loadSaga(String tradeId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select saga_status, cover_status, risk_status, accounting_status, settlement_status, " +
                             "notification_status, compliance_status from trade_saga where trade_id = '" + tradeId + "'"
             )) {
            if (!resultSet.next()) {
                throw new AssertionError("trade_saga row not found for tradeId=" + tradeId);
            }
            return Map.of(
                    "saga_status", resultSet.getString("saga_status"),
                    "cover_status", resultSet.getString("cover_status"),
                    "risk_status", resultSet.getString("risk_status"),
                    "accounting_status", resultSet.getString("accounting_status"),
                    "settlement_status", resultSet.getString("settlement_status"),
                    "notification_status", resultSet.getString("notification_status"),
                    "compliance_status", resultSet.getString("compliance_status")
            );
        }
    }

    private static Map<String, String> loadProjection(String tradeId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        ); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "select saga_status, notification_status from trade_query_projection where trade_id = '" + tradeId + "'"
             )) {
            if (!resultSet.next()) {
                return null;
            }
            return Map.of(
                    "saga_status", resultSet.getString("saga_status"),
                    "notification_status", resultSet.getString("notification_status")
            );
        }
    }
}
