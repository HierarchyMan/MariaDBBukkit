package com.mariadbbukkit.application.service;

import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.database.DatabaseConnectionPool;
import com.mariadbbukkit.domain.model.database.DatabaseServer;
import com.mariadbbukkit.domain.model.platform.PlatformDetector;
import com.mariadbbukkit.domain.model.provision.Provisioner;
import com.mariadbbukkit.domain.service.DatabaseLifecycleManager;
import com.mariadbbukkit.infrastructure.database.mariadb.MariaDBServer;
import com.mariadbbukkit.infrastructure.platform.SystemPlatformDetector;
import com.mariadbbukkit.infrastructure.pool.HikariConnectionPool;
import com.mariadbbukkit.infrastructure.provision.mariadb.MariaDBBinaryProvisioner;
import com.mariadbbukkit.infrastructure.provision.mariadb.MariaDBTermuxProvisioner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public final class DatabaseApplicationService {

    private final Logger log;
    private final PlatformDetector platformDetector;
    private DatabaseLifecycleManager lifecycleManager;

    public DatabaseApplicationService(Logger log) {
        this.log = log;
        this.platformDetector = new SystemPlatformDetector();
    }

    // Constructor with custom platform detector for testing
    public DatabaseApplicationService(Logger log, PlatformDetector platformDetector) {
        this.log = log;
        this.platformDetector = platformDetector;
    }

    public synchronized void start(DatabaseConfig config) throws Exception {
        if (lifecycleManager != null && lifecycleManager.isRunning()) {
            log.warning("Database service is already running.");
            return;
        }

        DatabaseServer server = new MariaDBServer(log);
        DatabaseConnectionPool pool = new HikariConnectionPool(log);
        
        Provisioner provisioner;
        if (config.getTermux().getPrefix() != null) {
            provisioner = new MariaDBTermuxProvisioner(log);
        } else {
            provisioner = new MariaDBBinaryProvisioner(log, platformDetector);
        }

        lifecycleManager = new DatabaseLifecycleManager(log, server, pool, provisioner);
        lifecycleManager.start(config);
    }

    public synchronized void stop() {
        if (lifecycleManager != null) {
            lifecycleManager.stop();
            lifecycleManager = null;
        }
    }

    public synchronized void restart(DatabaseConfig config) throws Exception {
        stop();
        start(config);
    }

    public synchronized boolean isRunning() {
        return lifecycleManager != null && lifecycleManager.isRunning();
    }

    public synchronized DataSource getDataSource() {
        return lifecycleManager != null ? lifecycleManager.getDataSource() : null;
    }

    public synchronized Connection getConnection() throws SQLException {
        if (lifecycleManager == null) {
            throw new SQLException("Database is not running.");
        }
        return lifecycleManager.getConnection();
    }

    public synchronized String getJdbcUrl(String dbName) {
        return lifecycleManager != null ? lifecycleManager.getJdbcUrl(dbName) : null;
    }

    public synchronized int getPort() {
        return lifecycleManager != null ? lifecycleManager.getPort() : -1;
    }

    public PlatformDetector getPlatformDetector() {
        return platformDetector;
    }
}
