package com.example.fx.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxBacklogMetrics {
    public OutboxBacklogMetrics(
            JdbcTemplate jdbcTemplate,
            MeterRegistry meterRegistry,
            @Value("${spring.application.name}") String applicationName
    ) {
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

    private double count(JdbcTemplate jdbcTemplate, String sql, String sourceService) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, sourceService);
        return count == null ? 0.0d : count.doubleValue();
    }
}
