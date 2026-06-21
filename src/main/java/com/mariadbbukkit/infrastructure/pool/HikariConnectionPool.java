package com.mariadbbukkit.infrastructure.pool;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.database.DatabaseConnectionPool;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HikariConnectionPool implements DatabaseConnectionPool {

    private final Logger log;
    private HikariDataSource pool;

    public HikariConnectionPool(Logger log) {
        this.log = log;
    }

    @Override
    public void init(String jdbcUrl, String username, String password, DatabaseConfig.Pool poolConfig, String driverClassName) throws Exception {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MariaDBBukkit-Pool");
        hikari.setJdbcUrl(jdbcUrl);
        hikari.setUsername(username);
        hikari.setPassword(password);

        String driverClass = driverClassName != null ? driverClassName : resolveDriverClassName();
        if (driverClass != null) {
            hikari.setDriverClassName(driverClass);
        }

        hikari.setMaximumPoolSize(poolConfig.getMaxPoolSize());
        hikari.setMinimumIdle(poolConfig.getMinimumIdle());
        hikari.setConnectionTimeout(poolConfig.getConnectionTimeoutMs());
        hikari.setIdleTimeout(poolConfig.getIdleTimeoutMs());
        hikari.setMaxLifetime(poolConfig.getMaxLifetimeMs());
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        pool = new HikariDataSource(hikari);
        log.info("Connection pool ready: " + hikari.getJdbcUrl() + " (user=" + username + ")");
    }

    private String resolveDriverClassName() {
        try {
            var loader = ServiceLoader.load(java.sql.Driver.class, getClass().getClassLoader());
            for (var provider : loader) {
                String name = provider.getClass().getName();
                if (name.contains(".jdbc.")) {
                    log.fine("Resolved JDBC driver class: " + name);
                    return name;
                }
            }
            if (loader.iterator().hasNext()) {
                String name = loader.iterator().next().getClass().getName();
                log.fine("Resolved JDBC driver class (first): " + name);
                return name;
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "ServiceLoader driver lookup failed", t);
        }
        return null;
    }

    @Override
    public DataSource getDataSource() {
        return pool;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (pool == null) {
            throw new SQLException("Database connection pool is not initialized.");
        }
        return pool.getConnection();
    }

    @Override
    public boolean isClosed() {
        return pool == null || pool.isClosed();
    }

    @Override
    public void close() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception e) {
                log.log(Level.WARNING, "Error closing connection pool", e);
            }
            pool = null;
        }
    }
}
