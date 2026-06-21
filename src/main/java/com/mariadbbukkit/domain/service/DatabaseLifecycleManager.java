package com.mariadbbukkit.domain.service;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.database.DatabaseConnectionPool;
import com.mariadbbukkit.domain.model.database.DatabaseServer;
import com.mariadbbukkit.domain.model.provision.Provisioner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public final class DatabaseLifecycleManager {

    private final Logger log;
    private final DatabaseServer server;
    private final DatabaseConnectionPool pool;
    private final Provisioner provisioner;

    public DatabaseLifecycleManager(Logger log, DatabaseServer server, DatabaseConnectionPool pool, Provisioner provisioner) {
        this.log = log;
        this.server = server;
        this.pool = pool;
        this.provisioner = provisioner;
    }

    public synchronized void start(DatabaseConfig config) throws Exception {
        log.info("Starting database lifecycle orchestration...");

        // 1. Provision binaries
        provisioner.ensureBinaries(config);

        // 2. Provision data directory (e.g. for Termux)
        provisioner.ensureDataDir(config);

        // 3. Start DB process
        server.start(config);

        // 4. Initialize Connection Pool
        String jdbcUrl = server.getJdbcUrl(config.getCredentials().getDbName());
        pool.init(
                jdbcUrl,
                config.getCredentials().getUsername(),
                config.getCredentials().getPassword(),
                config.getPool(),
                null
        );
    }

    public synchronized void stop() {
        log.info("Stopping database lifecycle orchestration...");
        try {
            if (pool != null) {
                pool.close();
            }
        } catch (Exception e) {
            log.warning("Failed to close connection pool: " + e.getMessage());
        }

        try {
            if (server != null) {
                server.stop();
            }
        } catch (Exception e) {
            log.warning("Failed to stop database server: " + e.getMessage());
        }
    }

    public synchronized boolean isRunning() {
        return pool != null && !pool.isClosed();
    }

    public DataSource getDataSource() {
        return pool != null ? pool.getDataSource() : null;
    }

    public Connection getConnection() throws SQLException {
        if (pool == null) {
            throw new SQLException("Database is not running.");
        }
        return pool.getConnection();
    }

    public String getJdbcUrl(String dbName) {
        return server != null ? server.getJdbcUrl(dbName) : null;
    }

    public int getPort() {
        return server != null ? server.getPort() : -1;
    }
}
