package com.example.fx.common.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ActivityJdbcSupport {
    private final JdbcOperations jdbcOperations;

    public ActivityJdbcSupport(Environment environment, JdbcTemplate defaultJdbcTemplate) {
        String url = environment.getProperty("activity.db.url");
        if (url == null || url.isBlank()) {
            this.jdbcOperations = defaultJdbcTemplate;
            return;
        }

        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(url)
                .username(environment.getProperty("activity.db.username"))
                .password(environment.getProperty("activity.db.password"))
                .build();
        dataSource.setMaximumPoolSize(Integer.parseInt(environment.getProperty("activity.db.pool.max-size", "10")));
        dataSource.setPoolName("activity-hikari");
        this.jdbcOperations = new JdbcTemplate(dataSource);
    }

    public JdbcOperations jdbcOperations() {
        return jdbcOperations;
    }
}
