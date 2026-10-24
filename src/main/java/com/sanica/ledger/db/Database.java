package com.sanica.ledger.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;

/** Connection pool + schema migrations + jOOQ context. */
public final class Database implements AutoCloseable {

    static {
        System.setProperty("org.jooq.no-logo", "true");
        System.setProperty("org.jooq.no-tips", "true");
    }

    private final HikariDataSource dataSource;
    private final DSLContext dsl;

    private Database(HikariDataSource dataSource) {
        this.dataSource = dataSource;
        this.dsl = DSL.using(dataSource, SQLDialect.POSTGRES, new Settings().withRenderSchema(false));
    }

    public static Database connect(String jdbcUrl, String user, String password, int poolSize) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("ledger");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(password);
        // A fixed-size pool avoids connection churn under load; size it to the database, not to request threads.
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(poolSize);
        config.setConnectionTimeout(5_000);
        HikariDataSource dataSource = new HikariDataSource(config);

        Flyway.configure().dataSource(dataSource).load().migrate();
        return new Database(dataSource);
    }

    public DSLContext dsl() {
        return dsl;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
