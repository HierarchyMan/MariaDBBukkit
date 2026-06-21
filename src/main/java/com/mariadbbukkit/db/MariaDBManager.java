package com.mariadbbukkit.db;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import ch.vorburger.exec.ManagedProcessException;
import com.mariadbbukkit.util.Platforms;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Owns the lifecycle of the embedded MariaDB server and a HikariCP connection
 * pool that other plugins use.
 */
public final class MariaDBManager {

    private final Logger log;
    private final Path baseDir;
    private final Path dataDir;
    private final Path tmpDir;
    private final int configuredPort;
    private final String dbName;
    private final String username;
    private final String password;
    private final boolean skipGrantTables;
    private final int maxPoolSize;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final int minimumIdle;
    private final String termuxPrefix;

    private DBConfigurationBuilder builder;
    private DB db;
    private HikariDataSource pool;
    private int actualPort = -1;

    public MariaDBManager(Logger log, Config cfg) {
        this.log = log;
        this.baseDir = cfg.baseDir;
        this.dataDir = cfg.dataDir;
        this.tmpDir = cfg.tmpDir;
        this.configuredPort = cfg.port;
        this.dbName = cfg.dbName;
        this.username = cfg.username;
        this.password = cfg.password;
        this.skipGrantTables = cfg.skipGrantTables;
        this.maxPoolSize = cfg.maxPoolSize;
        this.connectionTimeoutMs = cfg.connectionTimeoutMs;
        this.idleTimeoutMs = cfg.idleTimeoutMs;
        this.maxLifetimeMs = cfg.maxLifetimeMs;
        this.minimumIdle = cfg.minimumIdle;
        this.termuxPrefix = cfg.termuxPrefix;
    }

    /** Starts MariaDB and the pool. */
    public synchronized void start() throws Exception {
        Platforms platform = Platforms.current();
        log.info("Starting embedded MariaDB (platform=" + platform
                + (termuxPrefix != null ? ", termux mode" : "") + ")...");

        builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(configuredPort);
        builder.setBaseDir(baseDir.toFile());
        builder.setLibDir(baseDir.resolve("lib").toFile());
        builder.setDataDir(dataDir.toFile());
        builder.setTmpDir(tmpDir.toAbsolutePath().toString());
        builder.setUnpackingFromClasspath(false);
        builder.setSecurityDisabled(skipGrantTables);
        builder.setDeletingTemporaryBaseAndDataDirsOnShutdown(false);

        if (termuxPrefix != null) {
            // Point MariaDB4j at the Termux-native binaries. The data dir must
            // already be initialised (handled by TermuxProvisioner) so MariaDB4j
            // skips its install() step, which would pass --basedir and hit the
            // Termux mariadb-install-db script bug.
            DBConfiguration.Executable[] execs = {
                    DBConfiguration.Executable.Server,
                    DBConfiguration.Executable.Client,
                    DBConfiguration.Executable.Dump,
                    DBConfiguration.Executable.PrintDefaults,
                    DBConfiguration.Executable.InstallDB,
            };
            String[] names = {"mariadbd", "mariadb", "mariadb-dump", "my_print_defaults", "mariadb-install-db"};
            for (int i = 0; i < execs.length; i++) {
                builder.setExecutable(execs[i], termuxPrefix + "/bin/" + names[i]);
            }
        }

        DBConfiguration config = builder.build();
        actualPort = config.getPort();

        db = DB.newEmbeddedDB(config);
        db.start();
        log.info("MariaDB is up on port " + actualPort + " (data: "
                + dataDir.toAbsolutePath() + ")");

        initSchema();

        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("MariaDBBukkit-Pool");
        hikari.setJdbcUrl(builder.getURL(dbName));
        hikari.setUsername(username);
        hikari.setPassword(password);
        // Paper's plugin classloader isolates ServiceLoader-registered JDBC drivers
        // from DriverManager, so explicitly resolve and load the shaded driver class.
        String driverClass = resolveDriverClassName();
        if (driverClass != null) {
            hikari.setDriverClassName(driverClass);
        }
        hikari.setMaximumPoolSize(maxPoolSize);
        hikari.setMinimumIdle(minimumIdle);
        hikari.setConnectionTimeout(connectionTimeoutMs);
        hikari.setIdleTimeout(idleTimeoutMs);
        hikari.setMaxLifetime(maxLifetimeMs);
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        pool = new HikariDataSource(hikari);
        log.info("Connection pool ready: " + hikari.getJdbcUrl() + " (user=" + username + ")");
    }

    /** Creates the database and configured user (best-effort). */
    private void initSchema() {
        try {
            db.createDB(dbName);
        } catch (ManagedProcessException e) {
            log.log(Level.WARNING, "Failed to create database " + dbName, e);
            return;
        }
        if (skipGrantTables) {
            // With --skip-grant-tables, the server accepts any username/password
            // over JDBC without authentication. CREATE USER / GRANT are rejected
            // (ERROR 1290), and running FLUSH PRIVILEGES would enable auth and
            // break the pool connection. So we just create the database and
            // rely on skip-grant-tables for access. The configured username/
            // password still work for JDBC clients.
            log.info("Database '" + dbName + "' ready (skip-grant-tables: any"
                    + " credentials accepted, user '" + username + "' will work over JDBC).");
        } else {
            // Without skip-grant-tables, provision the user properly.
            String u = escape(username);
            String p = escape(password);
            String d = dbName.replace("`", "``");
            String sql = "CREATE USER IF NOT EXISTS '" + u + "'@'localhost' IDENTIFIED BY '" + p + "';"
                    + "CREATE USER IF NOT EXISTS '" + u + "'@'%' IDENTIFIED BY '" + p + "';"
                    + "GRANT ALL PRIVILEGES ON `" + d + "`.* TO '" + u + "'@'localhost';"
                    + "GRANT ALL PRIVILEGES ON `" + d + "`.* TO '" + u + "'@'%';"
                    + "FLUSH PRIVILEGES;";
            try {
                db.run(sql);
                log.info("Database '" + dbName + "' and user '" + username + "' provisioned.");
            } catch (ManagedProcessException e) {
                log.log(Level.WARNING, "Failed to provision user (database still created)", e);
            }
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /**
     * Finds the shaded MariaDB JDBC driver class name via the plugin classloader's
     * ServiceLoader. Robust against the maven-shade relocation (the class name
     * changes when the {@code org.mariadb.jdbc} package is relocated).
     */
    private String resolveDriverClassName() {
        try {
            var loader = java.util.ServiceLoader.load(java.sql.Driver.class, getClass().getClassLoader());
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

    public synchronized void stop() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception e) {
                log.log(Level.WARNING, "Error closing pool", e);
            }
            pool = null;
        }
        if (db != null) {
            try {
                db.stop();
            } catch (ManagedProcessException e) {
                log.log(Level.WARNING, "Error stopping MariaDB", e);
            }
            db = null;
        }
        log.info("MariaDB stopped.");
    }

    public synchronized boolean isRunning() {
        return pool != null && !pool.isClosed();
    }

    public synchronized DataSource getDataSource() {
        return pool;
    }

    public synchronized Connection getConnection() throws SQLException {
        if (pool == null) {
            throw new SQLException("MariaDBBukkit is not running.");
        }
        return pool.getConnection();
    }

    public synchronized String getJdbcUrl() {
        return builder != null ? builder.getURL(dbName) : null;
    }

    public synchronized int getPort() {
        return actualPort;
    }

    /** Plain config holder decoupled from Bukkit. */
    public static final class Config {
        public Path baseDir, dataDir, tmpDir;
        public int port;
        public String dbName, username, password;
        public boolean skipGrantTables;
        public int maxPoolSize, minimumIdle;
        public long connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs;
        /** Non-null when running under Termux: host MariaDB prefix to use. */
        public String termuxPrefix;
    }
}
