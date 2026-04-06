package com.example.fx.common.event;

public final class EventTypes {
    public static final String TRADE_EXECUTED = "TradeExecuted";
    public static final String COVER_TRADE_BOOKED = "CoverTradeBooked";
    public static final String COVER_TRADE_FAILED = "CoverTradeFailed";
    public static final String RISK_UPDATED = "RiskUpdated";
    public static final String RISK_UPDATE_FAILED = "RiskUpdateFailed";
    public static final String ACCOUNTING_POSTED = "AccountingPosted";
    public static final String ACCOUNTING_POST_FAILED = "AccountingPostFailed";
    public static final String SETTLEMENT_START_REQUESTED = "SettlementStartRequested";
    public static final String SETTLEMENT_RESERVED = "SettlementReserved";
    public static final String SETTLEMENT_RESERVE_FAILED = "SettlementReserveFailed";
    public static final String TRADE_NOTIFICATION_SENT = "TradeNotificationSent";
    public static final String TRADE_NOTIFICATION_FAILED = "TradeNotificationFailed";
    public static final String COMPLIANCE_CHECKED = "ComplianceChecked";
    public static final String COMPLIANCE_CHECK_FAILED = "ComplianceCheckFailed";
    public static final String REVERSE_COVER_TRADE_REQUESTED = "ReverseCoverTradeRequested";
    public static final String REVERSE_RISK_REQUESTED = "ReverseRiskRequested";
    public static final String REVERSE_ACCOUNTING_REQUESTED = "ReverseAccountingRequested";
    public static final String CANCEL_SETTLEMENT_REQUESTED = "CancelSettlementRequested";
    public static final String SEND_CORRECTION_NOTICE_REQUESTED = "SendCorrectionNoticeRequested";
    public static final String COVER_TRADE_REVERSED = "CoverTradeReversed";
    public static final String RISK_REVERSED = "RiskReversed";
    public static final String ACCOUNTING_REVERSED = "AccountingReversed";
    public static final String SETTLEMENT_CANCELLED = "SettlementCancelled";
    public static final String CORRECTION_NOTICE_SENT = "CorrectionNoticeSent";

    private EventTypes() {
    }
}
