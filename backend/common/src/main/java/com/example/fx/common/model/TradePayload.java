package com.example.fx.common.model;

import java.math.BigDecimal;

public record TradePayload(
        String tradeId,
        String orderId,
        String accountId,
        String currencyPair,
        String side,
        BigDecimal orderAmount,
        BigDecimal executedAmount,
        BigDecimal executedPrice,
        String correlationId,
        boolean simulateCoverFailure,
        boolean simulateRiskFailure,
        boolean simulateAccountingFailure,
        boolean simulateSettlementFailure,
        boolean simulateNotificationFailure,
        boolean simulateComplianceFailure
) {
}
