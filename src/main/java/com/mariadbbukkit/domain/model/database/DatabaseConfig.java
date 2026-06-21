package com.mariadbbukkit.domain.model.database;

import java.nio.file.Path;

public final class DatabaseConfig {

    private final DatabaseType type;
    private final Port port;
    private final Credentials credentials;
    private final Storage storage;
    private final Pool pool;
    private final Security security;
    private final Termux termux;
    private final Download download;

    public DatabaseConfig(DatabaseType type, Port port, Credentials credentials, Storage storage, Pool pool, Security security, Termux termux, Download download) {
        this.type = type;
        this.port = port;
        this.credentials = credentials;
        this.storage = storage;
        this.pool = pool;
        this.security = security;
        this.termux = termux;
        this.download = download;
    }

    public DatabaseType getType() { return type; }
    public Port getPort() { return port; }
    public Credentials getCredentials() { return credentials; }
    public Storage getStorage() { return storage; }
    public Pool getPool() { return pool; }
    public Security getSecurity() { return security; }
    public Termux getTermux() { return termux; }
    public Download getDownload() { return download; }

    public static final class Port {
        private final int value;
        public Port(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    public static final class Credentials {
        private final String dbName;
        private final String username;
        private final String password;
        public Credentials(String dbName, String username, String password) {
            this.dbName = dbName;
            this.username = username;
            this.password = password;
        }
        public String getDbName() { return dbName; }
        public String getUsername() { return username; }
        public String getPassword() { return password; }
    }

    public static final class Storage {
        private final Path baseDir;
        private final Path dataDir;
        private final Path tmpDir;
        public Storage(Path baseDir, Path dataDir, Path tmpDir) {
            this.baseDir = baseDir;
            this.dataDir = dataDir;
            this.tmpDir = tmpDir;
        }
        public Path getBaseDir() { return baseDir; }
        public Path getDataDir() { return dataDir; }
        public Path getTmpDir() { return tmpDir; }
    }

    public static final class Pool {
        private final int maxPoolSize;
        private final int minimumIdle;
        private final long connectionTimeoutMs;
        private final long idleTimeoutMs;
        private final long maxLifetimeMs;
        public Pool(int maxPoolSize, int minimumIdle, long connectionTimeoutMs, long idleTimeoutMs, long maxLifetimeMs) {
            this.maxPoolSize = maxPoolSize;
            this.minimumIdle = minimumIdle;
            this.connectionTimeoutMs = connectionTimeoutMs;
            this.idleTimeoutMs = idleTimeoutMs;
            this.maxLifetimeMs = maxLifetimeMs;
        }
        public int getMaxPoolSize() { return maxPoolSize; }
        public int getMinimumIdle() { return minimumIdle; }
        public long getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public long getIdleTimeoutMs() { return idleTimeoutMs; }
        public long getMaxLifetimeMs() { return maxLifetimeMs; }
    }

    public static final class Security {
        private final boolean skipGrantTables;
        public Security(boolean skipGrantTables) { this.skipGrantTables = skipGrantTables; }
        public boolean isSkipGrantTables() { return skipGrantTables; }
    }

    public static final class Termux {
        private final boolean autoInstall;
        private final String prefix;
        public Termux(boolean autoInstall, String prefix) {
            this.autoInstall = autoInstall;
            this.prefix = prefix;
        }
        public boolean isAutoInstall() { return autoInstall; }
        public String getPrefix() { return prefix; }
    }

    public static final class Download {
        private final boolean enabled;
        private final String mariadbVersion;
        private final String sourceUrl;
        public Download(boolean enabled, String mariadbVersion, String sourceUrl) {
            this.enabled = enabled;
            this.mariadbVersion = mariadbVersion;
            this.sourceUrl = sourceUrl;
        }
        public boolean isEnabled() { return enabled; }
        public String getMariadbVersion() { return mariadbVersion; }
        public String getSourceUrl() { return sourceUrl; }
    }
}
