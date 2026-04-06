package com.example.fx.integration;

import com.example.fx.accounting.AccountingServiceApplication;
import com.example.fx.compliance.ComplianceServiceApplication;
import com.example.fx.cover.CoverServiceApplication;
import com.example.fx.core.FxCoreServiceApplication;
import com.example.fx.notification.NotificationServiceApplication;
import com.example.fx.risk.RiskServiceApplication;
import com.example.fx.saga.TradeSagaServiceApplication;
import com.example.fx.settlement.SettlementServiceApplication;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class TradeFlowIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    static final int FX_CORE_PORT = 18080;

    @BeforeAll
    static void startSystem() throws Exception {
        POSTGRES.start();
        KAFKA.start();
        initSchema();

        CONTEXTS.add(startApp(FxCoreServiceApplication.class, FX_CORE_PORT));
        CONTEXTS.add(startApp(TradeSagaServiceApplication.class, 18081));
        CONTEXTS.add(startApp(CoverServiceApplication.class, 18082));
        CONTEXTS.add(startApp(RiskServiceApplication.class, 18083));
        CONTEXTS.add(startApp(AccountingServiceApplication.class, 18084));
        CONTEXTS.add(startApp(SettlementServiceApplication.class, 18085));
        CONTEXTS.add(startApp(NotificationServiceApplication.class, 18086));
        CONTEXTS.add(startApp(ComplianceServiceApplication.class, 18087));

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
                    assertTrue(Set.of("NOT_STARTED", "COMPENSATED").contains(saga.get("notification_status")), saga.toString());
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
}
