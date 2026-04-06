package com.example.fx.core.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SagaQueryJdbcSupport {
    private final JdbcOperations jdbcOperations;

    public SagaQueryJdbcSupport(Environment environment, JdbcTemplate defaultJdbcTemplate) {
        String url = environment.getProperty("saga.db.url");
        if (url == null || url.isBlank()) {
            this.jdbcOperations = defaultJdbcTemplate;
            return;
        }

        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(environment.getProperty("saga.db.username"))
                .password(environment.getProperty("saga.db.password"))
                .build();
        dataSource.setMaximumPoolSize(Integer.parseInt(environment.getProperty("saga.db.pool.max-size", "5")));
        dataSource.setPoolName("saga-query-hikari");
        this.jdbcOperations = new JdbcTemplate(dataSource);
    }

    public JdbcOperations jdbcOperations() {
        return jdbcOperations;
    }
}
