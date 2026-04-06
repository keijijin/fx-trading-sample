package com.example.fx.core;

import com.example.fx.common.activity.TradeActivitySupport;
import com.example.fx.common.db.ActivityJdbcSupport;
import com.example.fx.common.event.EventTypes;
import com.example.fx.common.kafka.KafkaConsumerUris;
import com.example.fx.common.json.JsonSupport;
import com.example.fx.common.model.TradePayload;
import com.example.fx.common.outbox.OutboxSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SpringBootApplication(scanBasePackages = {"com.example.fx.core", "com.example.fx.common"})
public class FxCoreServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FxCoreServiceApplication.class, args);
    }
}

@Component
class BalanceBucketSchemaSupport {
    private final JdbcTemplate jdbcTemplate;

    BalanceBucketSchemaSupport(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        jdbcTemplate.execute(
                "create table if not exists account_balance_bucket (" +
                        "account_id varchar(64) not null, " +
                        "currency varchar(3) not null, " +
                        "bucket_no integer not null, " +
                        "available_balance decimal(18,2) not null, " +
                        "held_balance decimal(18,2) not null, " +
                        "updated_at timestamp not null, " +
                        "version_no integer not null default 0, " +
                        "primary key (account_id, currency, bucket_no))"
        );
        jdbcTemplate.execute("alter table balance_hold add column if not exists bucket_no integer not null default 0");
        jdbcTemplate.execute(
                "create index if not exists idx_outbox_event_aggregate_id on outbox_event(aggregate_id)");
    }
}

@RestController
@RequestMapping("/api/trades")
class TradeController {
    private final TradeExecutionApplicationService tradeService;
    private final TradeTraceQueryService tradeTraceQueryService;

    TradeController(TradeExecutionApplicationService tradeService, TradeTraceQueryService tradeTraceQueryService) {
        this.tradeService = tradeService;
        this.tradeTraceQueryService = tradeTraceQueryService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TradeResponse createTrade(@RequestBody TradeRequest request) {
        return tradeService.execute(request);
    }

    @GetMapping("/{tradeId}/trace")
    TradeTraceResponse getTradeTrace(@PathVariable("tradeId") String tradeId) {
        return tradeTraceQueryService.getTrace(tradeId);
    }

    /**
     * Lightweight status for load-test / client polling: single round-trip to the saga DB only.
     * Avoids cross-DB fan-out and heavy activity/outbox queries on every poll.
     */
    @GetMapping("/{tradeId}/e2e-status")
    TradeE2eStatusResponse getE2eStatus(@PathVariable("tradeId") String tradeId) {
        return tradeTraceQueryService.getE2eStatus(tradeId);
    }
}

record TradeRequest(
        String orderId,
        String accountId,
        String currencyPair,
        String side,
        BigDecimal orderAmount,
        BigDecimal requestedPrice,
        boolean simulateCoverFailure,
        boolean simulateRiskFailure,
        boolean simulateAccountingFailure,
        boolean simulateSettlementFailure,
        boolean simulateNotificationFailure,
        boolean simulateComplianceFailure,
        boolean preTradeComplianceFailure
) {
}

record TradeResponse(
        String tradeId,
        String orderId,
        String sagaStatus,
        String correlationId
) {
}

@org.springframework.stereotype.Service
class TradeExecutionApplicationService {
    private static final BigDecimal DEFAULT_INITIAL_BALANCE = new BigDecimal("1000000.00");
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final JsonSupport jsonSupport;
    private final TradeActivitySupport tradeActivitySupport;
    private final MeterRegistry meterRegistry;
    private final int balanceBucketCount;
    private final Set<String> initializedBalanceBuckets = ConcurrentHashMap.newKeySet();
    private final long slowOperationThresholdMs = 200L;
    private final long slowLockThresholdMs = 50L;

    TradeExecutionApplicationService(
            JdbcTemplate jdbcTemplate,
            OutboxSupport outboxSupport,
            JsonSupport jsonSupport,
            TradeActivitySupport tradeActivitySupport,
            MeterRegistry meterRegistry,
            @Value("${balance.bucket.count:16}") int balanceBucketCount
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxSupport = outboxSupport;
        this.jsonSupport = jsonSupport;
        this.tradeActivitySupport = tradeActivitySupport;
        this.meterRegistry = meterRegistry;
        this.balanceBucketCount = balanceBucketCount;
    }

    @Transactional
    public TradeResponse execute(TradeRequest request) {
        long transactionStart = System.nanoTime();
        validate(request);

        if (request.preTradeComplianceFailure()) {
            recordTransactionDuration("rejected", transactionStart);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pre-trade compliance rejected");
        }
        try {
            String orderId = normalizeOrRandom(request.orderId(), "ORD");
            String tradeId = "TRD-" + UUID.randomUUID();
            String correlationId = "CORR-" + UUID.randomUUID();
            String nowCurrency = settlementCurrency(request.currencyPair());
            Instant now = Instant.now();

            tradeActivitySupport.record(
                    tradeId,
                    "fx-core-service",
                    "API_REQUEST_ACCEPTED",
                    "RUNNING",
                    "Trade request accepted by FX Core Service",
                    null,
                    null
            );

            createOrder(orderId, request, now);
            ensureBalanceBuckets(request.accountId(), nowCurrency, now);
            acquireBalanceSerializationLock(request.accountId(), nowCurrency);
            List<BalanceHoldAllocation> holdAllocations = applyBalanceHold(
                    tradeId,
                    request.accountId(),
                    nowCurrency,
                    request.orderAmount(),
                    now
            );
            createExecution(tradeId, orderId, request, now);
            createBalanceHolds(tradeId, request.accountId(), holdAllocations, now);

            TradePayload payload = new TradePayload(
                    tradeId,
                    orderId,
                    request.accountId(),
                    request.currencyPair(),
                    request.side(),
                    request.orderAmount(),
                    request.orderAmount(),
                    request.requestedPrice().setScale(8, RoundingMode.HALF_UP),
                    correlationId,
                    request.simulateCoverFailure(),
                    request.simulateRiskFailure(),
                    request.simulateAccountingFailure(),
                    request.simulateSettlementFailure(),
                    request.simulateNotificationFailure(),
                    request.simulateComplianceFailure()
            );

            outboxSupport.enqueue(
                    "fx-core-service",
                    "trade",
                    tradeId,
                    EventTypes.TRADE_EXECUTED,
                    "fx-trade-events",
                    tradeId,
                    jsonSupport.write(payload),
                    correlationId
            );

            tradeActivitySupport.record(
                    tradeId,
                    "fx-core-service",
                    "ACID_COMMIT",
                    "COMPLETED",
                    "Trade execution, balance hold, position update, saga seed, and outbox registration committed",
                    EventTypes.TRADE_EXECUTED,
                    "fx-trade-events"
            );
            Counter.builder("fx_trade_submitted_total")
                    .tag("currency_pair", request.currencyPair())
                    .tag("side", request.side().toUpperCase())
                    .register(meterRegistry)
                    .increment();

            recordTransactionDuration("committed", transactionStart);
            return new TradeResponse(tradeId, orderId, "PENDING", correlationId);
        } catch (RuntimeException exception) {
            recordTransactionDuration("failed", transactionStart);
            throw exception;
        }
    }

    private void validate(TradeRequest request) {
        if (request.accountId() == null || request.accountId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "accountId is required");
        }
        if (request.currencyPair() == null || request.currencyPair().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currencyPair is required");
        }
        if (request.side() == null || request.side().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "side is required");
        }
        if (request.orderAmount() == null || request.orderAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderAmount must be positive");
        }
        if (request.requestedPrice() == null || request.requestedPrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "requestedPrice must be positive");
        }
    }

    private String normalizeOrRandom(String value, String prefix) {
        if (value == null || value.isBlank()) {
            return prefix + "-" + UUID.randomUUID();
        }
        return value;
    }

    private void createOrder(String orderId, TradeRequest request, Instant now) {
        try {
            recordOperation("insert_trade_order_executing", () -> jdbcTemplate.update(
                    "insert into trade_order (" +
                            "order_id, account_id, currency_pair, side, order_type, order_amount, order_status, created_at, updated_at, version_no" +
                            ") values (?, ?, ?, ?, 'MARKET', ?, 'EXECUTED', ?, ?, 0)",
                    orderId,
                    request.accountId(),
                    request.currencyPair(),
                    request.side(),
                    request.orderAmount(),
                    Timestamp.from(now),
                    Timestamp.from(now)
            ));
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate execution detected");
        }
    }

    private void acquireBalanceSerializationLock(String accountId, String currency) {
        recordOperation("pg_advisory_xact_lock_balance", () ->
                jdbcTemplate.execute((ConnectionCallback<Void>) con -> {
                    try (PreparedStatement ps = con.prepareStatement(
                            "select pg_advisory_xact_lock(hashtext(?::text), hashtext(?::text))")) {
                        ps.setString(1, accountId);
                        ps.setString(2, currency);
                        ps.execute();
                    }
                    return null;
                }));
    }

    private void ensureBalanceBuckets(String accountId, String currency, Instant now) {
        String key = accountId + "|" + currency;
        if (initializedBalanceBuckets.contains(key)) {
            return;
        }

        boolean bucketExists = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists(select 1 from account_balance_bucket where account_id = ? and currency = ?)",
                Boolean.class,
                accountId,
                currency
        ));
        if (bucketExists) {
            initializedBalanceBuckets.add(key);
            return;
        }

        Map<String, Object> legacyBalance = loadLegacyBalance(accountId, currency);
        BigDecimal available = legacyBalance == null
                ? DEFAULT_INITIAL_BALANCE
                : (BigDecimal) legacyBalance.get("available_balance");
        BigDecimal held = legacyBalance == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : (BigDecimal) legacyBalance.get("held_balance");
        seedBalanceBuckets(accountId, currency, available, held, now);
        initializedBalanceBuckets.add(key);
    }

    private List<BalanceHoldAllocation> applyBalanceHold(
            String tradeId,
            String accountId,
            String currency,
            BigDecimal amount,
            Instant now
    ) {
        BigDecimal remaining = amount.setScale(2, RoundingMode.HALF_UP);
        List<BalanceHoldAllocation> allocations = new ArrayList<>();
        int preferredBucket = preferredBucket(tradeId);
        for (int offset = 0; offset < balanceBucketCount && remaining.compareTo(BigDecimal.ZERO) > 0; offset++) {
            int bucketNo = (preferredBucket + offset) % balanceBucketCount;
            BigDecimal applied = holdFromBucket(accountId, currency, bucketNo, remaining, now);
            if (applied.compareTo(BigDecimal.ZERO) > 0) {
                allocations.add(new BalanceHoldAllocation(bucketNo, applied));
                remaining = remaining.subtract(applied);
            }
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient available balance");
        }
        return allocations;
    }

    private void createExecution(String tradeId, String orderId, TradeRequest request, Instant now) {
        recordOperation("insert_trade_execution", () -> jdbcTemplate.update(
                "insert into trade_execution (" +
                        "trade_id, order_id, account_id, currency_pair, side, executed_price, executed_amount, execution_status, executed_at, version_no" +
                        ") values (?, ?, ?, ?, ?, ?, ?, 'EXECUTED', ?, 0)",
                tradeId,
                orderId,
                request.accountId(),
                request.currencyPair(),
                request.side(),
                request.requestedPrice().setScale(8, RoundingMode.HALF_UP),
                request.orderAmount(),
                Timestamp.from(now)
        ));
    }

    private void createBalanceHolds(String tradeId, String accountId, List<BalanceHoldAllocation> allocations, Instant now) {
        recordOperation("insert_balance_hold", () -> {
            Timestamp timestamp = Timestamp.from(now);
            List<Object[]> batchArgs = new ArrayList<>(allocations.size());
            for (BalanceHoldAllocation allocation : allocations) {
                batchArgs.add(new Object[]{
                        "HLD-" + UUID.randomUUID(),
                        tradeId,
                        accountId,
                        allocation.bucketNo(),
                        allocation.amount(),
                        timestamp,
                        timestamp
                });
            }
            jdbcTemplate.batchUpdate(
                    "insert into balance_hold " +
                            "(hold_id, trade_id, account_id, bucket_no, hold_amount, hold_status, created_at, updated_at) " +
                            "values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?)",
                    batchArgs
            );
        });
    }

    private String settlementCurrency(String currencyPair) {
        String[] parts = currencyPair.split("/");
        return parts.length > 0 ? parts[0] : currencyPair.substring(0, Math.min(3, currencyPair.length()));
    }

    private void seedBalanceBuckets(String accountId, String currency, BigDecimal available, BigDecimal held, Instant now) {
        List<BigDecimal> availableParts = splitAmountAcrossBuckets(available);
        List<BigDecimal> heldParts = splitAmountAcrossBuckets(held);
        recordOperation("insert_account_balance_bucket_seed", () -> {
            Timestamp timestamp = Timestamp.from(now);
            List<Object[]> batchArgs = new ArrayList<>(balanceBucketCount);
            for (int bucketNo = 0; bucketNo < balanceBucketCount; bucketNo++) {
                batchArgs.add(new Object[]{
                        accountId,
                        currency,
                        bucketNo,
                        availableParts.get(bucketNo),
                        heldParts.get(bucketNo),
                        timestamp
                });
            }
            jdbcTemplate.batchUpdate(
                    "insert into account_balance_bucket " +
                            "(account_id, currency, bucket_no, available_balance, held_balance, updated_at, version_no) " +
                            "values (?, ?, ?, ?, ?, ?, 0) " +
                            "on conflict (account_id, currency, bucket_no) do nothing",
                    batchArgs
            );
        });
    }

    private Map<String, Object> loadLegacyBalance(String accountId, String currency) {
        try {
            return jdbcTemplate.queryForMap(
                    "select available_balance, held_balance from account_balance where account_id = ? and currency = ?",
                    accountId,
                    currency
            );
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private List<BigDecimal> splitAmountAcrossBuckets(BigDecimal totalAmount) {
        long cents = totalAmount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        long baseCents = cents / balanceBucketCount;
        long remainder = cents % balanceBucketCount;
        List<BigDecimal> parts = new ArrayList<>(balanceBucketCount);
        for (int bucketNo = 0; bucketNo < balanceBucketCount; bucketNo++) {
            long bucketCents = baseCents + (bucketNo < remainder ? 1 : 0);
            parts.add(BigDecimal.valueOf(bucketCents, 2));
        }
        return parts;
    }

    private int preferredBucket(String tradeId) {
        return Math.floorMod(tradeId.hashCode(), balanceBucketCount);
    }

    private BigDecimal holdFromBucket(String accountId, String currency, int bucketNo, BigDecimal remainingAmount, Instant now) {
        return recordLockWait("account_balance_bucket_hold", () -> recordOperationWithResult(
                "update_account_balance_bucket_hold",
                () -> {
                    try {
                        return jdbcTemplate.queryForObject(
                                "with selected as (" +
                                        "select available_balance from account_balance_bucket " +
                                        "where account_id = ? and currency = ? and bucket_no = ? and available_balance > 0 " +
                                        "for update), updated as (" +
                                        "update account_balance_bucket set " +
                                        "available_balance = available_balance - least((select available_balance from selected), ?), " +
                                        "held_balance = held_balance + least((select available_balance from selected), ?), " +
                                        "updated_at = ?, " +
                                        "version_no = version_no + 1 " +
                                        "where account_id = ? and currency = ? and bucket_no = ? and exists (select 1 from selected) " +
                                        "returning least((select available_balance from selected), ?) as applied_amount) " +
                                        "select applied_amount from updated",
                                BigDecimal.class,
                                accountId,
                                currency,
                                bucketNo,
                                remainingAmount,
                                remainingAmount,
                                Timestamp.from(now),
                                accountId,
                                currency,
                                bucketNo,
                                remainingAmount
                        );
                    } catch (EmptyResultDataAccessException exception) {
                        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                    }
                }
        ));
    }

    private BigDecimal signedAmount(String side, BigDecimal amount) {
        return "SELL".equalsIgnoreCase(side) ? amount.negate() : amount;
    }

    private void recordTransactionDuration(String outcome, long startNanos) {
        Timer.builder("fx_core_transaction_duration_seconds")
                .publishPercentileHistogram()
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(System.nanoTime() - startNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    private void recordOperation(String operation, Runnable runnable) {
        long start = System.nanoTime();
        runnable.run();
        recordDbDuration("fx_core_db_operation_duration_seconds", operation, start, slowOperationThresholdMs);
    }

    private <T> T recordOperationWithResult(String operation, java.util.concurrent.Callable<T> callable) {
        long start = System.nanoTime();
        try {
            return callable.call();
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(exception);
        } finally {
            recordDbDuration("fx_core_db_operation_duration_seconds", operation, start, slowOperationThresholdMs);
        }
    }

    private <T> T recordLockWait(String lockTarget, java.util.concurrent.Callable<T> callable) {
        long start = System.nanoTime();
        try {
            return callable.call();
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(exception);
        } finally {
            recordDbDuration("fx_core_lock_wait_seconds", lockTarget, start, slowLockThresholdMs);
        }
    }

    private void recordDbDuration(String metricName, String operation, long startNanos, long slowThresholdMs) {
        long elapsedNanos = System.nanoTime() - startNanos;
        Timer.builder(metricName)
                .publishPercentileHistogram()
                .tag("operation", operation)
                .register(meterRegistry)
                .record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        if ((elapsedNanos / 1_000_000L) >= slowThresholdMs) {
            Counter.builder("fx_core_slow_operation_total")
                    .tag("operation", operation)
                    .register(meterRegistry)
                    .increment();
        }
    }
}

record ServiceStatusView(
        String name,
        String status
) {
}

record EventView(
        String sourceService,
        String eventType,
        String topicName,
        String status,
        String createdAt,
        String sentAt
) {
}

record ActivityView(
        String serviceName,
        String activityType,
        String activityStatus,
        String detail,
        String eventType,
        String topicName,
        String createdAt
) {
}

record BalanceView(
        String currency,
        BigDecimal available,
        BigDecimal held
) {
}

record BalanceHoldAllocation(
        int bucketNo,
        BigDecimal amount
) {
}

record TradeTraceResponse(
        String tradeId,
        String orderId,
        String tradeStatus,
        String sagaStatus,
        String correlationId,
        BalanceView balance,
        String positionSummary,
        List<ServiceStatusView> services,
        List<EventView> events,
        List<ActivityView> activities
) {
}

/** Minimal saga row projection for E2E polling (one query to saga DB). */
record TradeE2eStatusResponse(
        String sagaStatus,
        String notificationStatus
) {
}

@org.springframework.stereotype.Service
class TradeTraceQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.jdbc.core.JdbcOperations sagaJdbcOperations;

    TradeTraceQueryService(JdbcTemplate jdbcTemplate, ActivityJdbcSupport activityJdbcSupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.sagaJdbcOperations = activityJdbcSupport.jdbcOperations();
    }

    public TradeE2eStatusResponse getE2eStatus(String tradeId) {
        try {
            return sagaJdbcOperations.queryForObject(
                    "select saga_status, notification_status from trade_saga where trade_id = ?",
                    (rs, rowNum) -> new TradeE2eStatusResponse(
                            rs.getString("saga_status"),
                            rs.getString("notification_status")
                    ),
                    tradeId
            );
        } catch (EmptyResultDataAccessException exception) {
            return new TradeE2eStatusResponse("PENDING", null);
        }
    }

    public TradeTraceResponse getTrace(String tradeId) {
        Map<String, Object> execution = jdbcTemplate.queryForMap(
                "select trade_id, order_id, account_id, currency_pair, execution_status from trade_execution where trade_id = ?",
                tradeId
        );
        Map<String, Object> saga = sagaJdbcOperations.queryForMap(
                "select * from trade_saga where trade_id = ?",
                tradeId
        );

        String accountId = stringValue(execution.get("account_id"));
        String currency = settlementCurrency(stringValue(execution.get("currency_pair")));

        Map<String, Object> balanceRow = jdbcTemplate.queryForMap(
                "select coalesce(sum(available_balance), 0.00) as available_balance, " +
                        "coalesce(sum(held_balance), 0.00) as held_balance " +
                        "from account_balance_bucket where account_id = ? and currency = ?",
                accountId,
                currency
        );

        String positionId = accountId + "-" + stringValue(execution.get("currency_pair"));
        String positionSummary;
        try {
            Map<String, Object> positionRow = jdbcTemplate.queryForMap(
                    "select net_amount, average_price from fx_position where position_id = ?",
                    positionId
            );
            positionSummary = "net=" + positionRow.get("net_amount") + ", avg=" + positionRow.get("average_price");
        } catch (EmptyResultDataAccessException exception) {
            positionSummary = "position not found";
        }

        List<ServiceStatusView> services = List.of(
                new ServiceStatusView("Cover", stringValue(saga.get("cover_status"))),
                new ServiceStatusView("Risk", stringValue(saga.get("risk_status"))),
                new ServiceStatusView("Accounting", stringValue(saga.get("accounting_status"))),
                new ServiceStatusView("Settlement", stringValue(saga.get("settlement_status"))),
                new ServiceStatusView("Notification", stringValue(saga.get("notification_status"))),
                new ServiceStatusView("Compliance", stringValue(saga.get("compliance_status")))
        );

        List<EventView> events = jdbcTemplate.query(
                "select source_service, event_type, topic_name, status, created_at, sent_at " +
                        "from outbox_event where aggregate_id = ? order by created_at",
                (rs, rowNum) -> new EventView(
                        rs.getString("source_service"),
                        rs.getString("event_type"),
                        rs.getString("topic_name"),
                        rs.getString("status"),
                        stringifyTimestamp(rs.getTimestamp("created_at")),
                        stringifyTimestamp(rs.getTimestamp("sent_at"))
                ),
                tradeId
        );

        List<ActivityView> activities = sagaJdbcOperations.query(
                "select service_name, activity_type, activity_status, detail, event_type, topic_name, created_at " +
                        "from trade_activity where trade_id = ? order by created_at",
                (rs, rowNum) -> new ActivityView(
                        rs.getString("service_name"),
                        rs.getString("activity_type"),
                        rs.getString("activity_status"),
                        rs.getString("detail"),
                        rs.getString("event_type"),
                        rs.getString("topic_name"),
                        stringifyTimestamp(rs.getTimestamp("created_at"))
                ),
                tradeId
        );

        return new TradeTraceResponse(
                tradeId,
                stringValue(execution.get("order_id")),
                stringValue(execution.get("execution_status")),
                stringValue(saga.get("saga_status")),
                stringValue(saga.get("correlation_id")),
                new BalanceView(
                        currency,
                        (BigDecimal) balanceRow.get("available_balance"),
                        (BigDecimal) balanceRow.get("held_balance")
                ),
                positionSummary,
                services,
                events,
                activities
        );
    }

    private String settlementCurrency(String currencyPair) {
        String[] parts = currencyPair.split("/");
        return parts.length > 0 ? parts[0] : currencyPair.substring(0, Math.min(3, currencyPair.length()));
    }

    private String stringifyTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }
}

@org.springframework.stereotype.Component
class OutboxPublisherRoute extends RouteBuilder {
    private final OutboxSupport outboxSupport;
    private final java.util.concurrent.ExecutorService outboxPool = java.util.concurrent.Executors.newFixedThreadPool(4);

    OutboxPublisherRoute(OutboxSupport outboxSupport) {
        this.outboxSupport = outboxSupport;
    }

    @Override
    public void configure() {
        from("timer:coreOutboxPoller?fixedRate=true&period={{outbox.poll.period.ms:100}}")
                .routeId("core-outbox-poller")
                .to("sql:select event_id from outbox_event where status in ('NEW','RETRY') " +
                        "and source_service = 'fx-core-service' " +
                        "and (next_retry_at is null or next_retry_at <= current_timestamp) " +
                        "order by created_at fetch first {{outbox.max.rows:100}} rows only" +
                        "?dataSource=#dataSource&outputType=SelectList")
                .split(body()).parallelProcessing().executorService(outboxPool)
                    .to("direct:publishOutboxEvent")
                .end();

        from("direct:publishOutboxEvent")
                .routeId("core-publish-outbox-event")
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
}

@org.springframework.stereotype.Component
class PositionProjectionConsumerRoute extends RouteBuilder {
    private final PositionProjectionService positionProjectionService;

    PositionProjectionConsumerRoute(PositionProjectionService positionProjectionService) {
        this.positionProjectionService = positionProjectionService;
    }

    @Override
    public void configure() {
        from(KafkaConsumerUris.consumer("fx-trade-events", "fx-core-position-service"))
                .routeId("fx-core-position-projection")
                .choice()
                    .when(header("eventType").isEqualTo(EventTypes.TRADE_EXECUTED))
                        .unmarshal().json(JsonLibrary.Jackson, TradePayload.class)
                        .bean(positionProjectionService, "project(${body}, ${header.eventId})")
                .end();
    }
}

@org.springframework.stereotype.Service
class PositionProjectionService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxSupport outboxSupport;
    private final MeterRegistry meterRegistry;

    PositionProjectionService(JdbcTemplate jdbcTemplate, OutboxSupport outboxSupport, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxSupport = outboxSupport;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public void project(TradePayload payload, String eventId) {
        if (outboxSupport.isDuplicate(eventId, "fx-core-position-service")) {
            return;
        }
        long startedAt = System.nanoTime();
        String positionId = payload.accountId() + "-" + payload.currencyPair();
        BigDecimal signedAmount = "SELL".equalsIgnoreCase(payload.side()) ? payload.orderAmount().negate() : payload.orderAmount();
        jdbcTemplate.update(
                "insert into fx_position (position_id, account_id, currency_pair, net_amount, average_price, updated_at, version_no) " +
                        "values (?, ?, ?, ?, ?, ?, 0) " +
                        "on conflict (position_id) do update set " +
                        "net_amount = fx_position.net_amount + excluded.net_amount, " +
                        "average_price = excluded.average_price, " +
                        "updated_at = excluded.updated_at, " +
                        "version_no = fx_position.version_no + 1",
                positionId,
                payload.accountId(),
                payload.currencyPair(),
                signedAmount,
                payload.executedPrice().setScale(8, RoundingMode.HALF_UP),
                Timestamp.from(Instant.now())
        );
        Timer.builder("fx_core_position_projection_duration_seconds")
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(System.nanoTime() - startedAt, java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
