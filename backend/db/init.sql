CREATE TABLE IF NOT EXISTS trade_order (
    order_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    currency_pair VARCHAR(16) NOT NULL,
    side VARCHAR(8) NOT NULL,
    order_type VARCHAR(16) NOT NULL,
    order_amount DECIMAL(18,2) NOT NULL,
    order_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS trade_execution (
    trade_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    currency_pair VARCHAR(16) NOT NULL,
    side VARCHAR(8) NOT NULL,
    executed_price DECIMAL(18,8) NOT NULL,
    executed_amount DECIMAL(18,2) NOT NULL,
    execution_status VARCHAR(32) NOT NULL,
    executed_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_trade_execution_order UNIQUE (order_id)
);

CREATE TABLE IF NOT EXISTS account_balance (
    account_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    available_balance DECIMAL(18,2) NOT NULL,
    held_balance DECIMAL(18,2) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, currency)
);

CREATE TABLE IF NOT EXISTS account_balance_bucket (
    account_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    bucket_no INTEGER NOT NULL,
    available_balance DECIMAL(18,2) NOT NULL,
    held_balance DECIMAL(18,2) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, currency, bucket_no)
);

CREATE TABLE IF NOT EXISTS balance_hold (
    hold_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    bucket_no INTEGER NOT NULL DEFAULT 0,
    hold_amount DECIMAL(18,2) NOT NULL,
    hold_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS fx_position (
    position_id VARCHAR(64) PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    currency_pair VARCHAR(16) NOT NULL,
    net_amount DECIMAL(18,2) NOT NULL,
    average_price DECIMAL(18,8) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS trade_saga (
    trade_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    saga_status VARCHAR(32) NOT NULL,
    cover_status VARCHAR(32) NOT NULL,
    risk_status VARCHAR(32) NOT NULL,
    accounting_status VARCHAR(32) NOT NULL,
    settlement_status VARCHAR(32) NOT NULL,
    notification_status VARCHAR(32) NOT NULL,
    compliance_status VARCHAR(32) NOT NULL,
    cover_cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    risk_cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    accounting_cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    settlement_cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_id VARCHAR(128) NOT NULL,
    cover_completed_at TIMESTAMP NULL,
    risk_completed_at TIMESTAMP NULL,
    accounting_completed_at TIMESTAMP NULL,
    settlement_requested_at TIMESTAMP NULL,
    settlement_completed_at TIMESTAMP NULL,
    notification_completed_at TIMESTAMP NULL,
    compliance_completed_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS outbox_event (
    event_id VARCHAR(64) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    topic_name VARCHAR(128) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP NULL,
    correlation_id VARCHAR(128) NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP NULL,
    last_error_message VARCHAR(1024),
    version_no INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_outbox_event_aggregate_id ON outbox_event(aggregate_id);
CREATE INDEX IF NOT EXISTS idx_outbox_event_poll ON outbox_event(source_service, status, created_at)
    WHERE status IN ('NEW', 'RETRY');

CREATE TABLE IF NOT EXISTS processed_message (
    consumer_name VARCHAR(64) NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    processed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE IF NOT EXISTS cover_trade (
    cover_trade_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    cover_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS risk_record (
    risk_record_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    risk_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS accounting_entry (
    accounting_entry_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    accounting_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS settlement_record (
    settlement_record_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    settlement_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS notification_record (
    notification_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    notification_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS compliance_record (
    compliance_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    compliance_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_activity (
    activity_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    service_name VARCHAR(64) NOT NULL,
    activity_type VARCHAR(64) NOT NULL,
    activity_status VARCHAR(64) NOT NULL,
    detail VARCHAR(1024) NOT NULL,
    event_type VARCHAR(64),
    topic_name VARCHAR(128),
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trade_activity_trade_id ON trade_activity(trade_id);

CREATE TABLE IF NOT EXISTS trade_query_projection (
    trade_id VARCHAR(64) PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    currency_pair VARCHAR(16) NOT NULL,
    trade_status VARCHAR(32) NOT NULL,
    saga_status VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    cover_status VARCHAR(32) NOT NULL,
    risk_status VARCHAR(32) NOT NULL,
    accounting_status VARCHAR(32) NOT NULL,
    settlement_status VARCHAR(32) NOT NULL,
    notification_status VARCHAR(32) NOT NULL,
    compliance_status VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS trade_event_journal (
    event_id VARCHAR(64) PRIMARY KEY,
    trade_id VARCHAR(64) NOT NULL,
    source_service VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    topic_name VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trade_event_journal_trade_id ON trade_event_journal(trade_id, created_at);
