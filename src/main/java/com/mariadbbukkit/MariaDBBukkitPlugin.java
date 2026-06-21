package com.mariadbbukkit;

import com.mariadbbukkit.db.MariaDBManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * MariaDBBukkit main plugin. Starts a portable embedded MariaDB at server
 * startup, downloading native binaries on first run if needed, and exposes a
 * pooled {@link DataSource} to other plugins via the Bukkit {@link ServicesManager}.
 */
public final class MariaDBBukkitPlugin extends JavaPlugin implements MariaDBService {

    private MariaDBManager manager;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        getLogger().info("MariaDBBukkit loading. Starting database...");
        try {
            manager = buildAndStartManager();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start embedded MariaDB during onLoad.", e);
            manager = null;
        }
    }

    @Override
    public void onEnable() {
        if (manager == null) {
            getLogger().severe("MariaDB manager is null. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getServicesManager().register(MariaDBService.class, this, this, ServicePriority.High);
        var cmd = getCommand("mariadbbukkit");
        if (cmd != null) {
            cmd.setExecutor(new MariaDBBukkitCommand(this));
        }
        getLogger().info("MariaDBBukkit ready. JDBC url: " + getJdbcUrl());
    }

    private MariaDBManager buildAndStartManager() throws Exception {
        var dataFolder = getDataFolder().toPath();
        Path baseDir = resolvePath(dataFolder, getConfig().getString("storage.base-dir", "mariadb"));
        Path dataDir = resolvePath(dataFolder, getConfig().getString("storage.data-dir", "data"));
        Path tmpDir = resolvePath(dataFolder, getConfig().getString("storage.tmp-dir", "tmp"));

        String termuxPrefix = com.mariadbbukkit.util.Platforms.termuxPrefix();

        if (termuxPrefix != null) {
            // Termux native mode: use host binaries, pre-init data dir.
            boolean autoInstall = getConfig().getBoolean("termux.auto-install", true);
            com.mariadbbukkit.db.TermuxProvisioner tp =
                    new com.mariadbbukkit.db.TermuxProvisioner(getLogger(), termuxPrefix, dataDir, autoInstall);
            tp.ensureBinaries();
            tp.ensureDataDir();
            baseDir = Path.of(termuxPrefix);
        } else {
            // Normal mode: make sure native binaries exist (download if missing).
            com.mariadbbukkit.db.BinaryProvisioner provisioner =
                    new com.mariadbbukkit.db.BinaryProvisioner(
                            getLogger(),
                            baseDir,
                            getConfig().getBoolean("download.enabled", true),
                            getConfig().getString("download.mariadb-version", "11.4.5"),
                            getConfig().getString("download.source-url", ""));
            provisioner.ensureBinaries();
        }

        // Build the manager config and start.
        var cfg = new MariaDBManager.Config();
        cfg.baseDir = baseDir;
        cfg.dataDir = dataDir;
        cfg.tmpDir = tmpDir;
        cfg.port = getConfig().getInt("database.port", 3306);
        cfg.dbName = getConfig().getString("database.name", "minecraft");
        cfg.username = getConfig().getString("database.username", "minecraft");
        cfg.password = getConfig().getString("database.password", "");
        cfg.skipGrantTables = getConfig().getBoolean("security.skip-grant-tables", true);
        cfg.maxPoolSize = getConfig().getInt("pool.maximum-pool-size", 10);
        cfg.minimumIdle = getConfig().getInt("pool.minimum-idle", 2);
        cfg.connectionTimeoutMs = getConfig().getLong("pool.connection-timeout-ms", 30000L);
        cfg.idleTimeoutMs = getConfig().getLong("pool.idle-timeout-ms", 600000L);
        cfg.maxLifetimeMs = getConfig().getLong("pool.max-lifetime-ms", 1800000L);
        cfg.termuxPrefix = termuxPrefix;

        MariaDBManager mgr = new MariaDBManager(getLogger(), cfg);
        mgr.start();
        return mgr;
    }

    private Path resolvePath(Path dataFolder, String configured) {
        Path p = Path.of(configured);
        return p.isAbsolute() ? p : dataFolder.resolve(p);
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        if (getServer() != null) {
            getServer().getServicesManager().unregister(MariaDBService.class, this);
        }
    }

    MariaDBManager getManager() {
        return manager;
    }

    // ---- MariaDBService ----

    @Override
    public DataSource getDataSource() {
        return manager != null ? manager.getDataSource() : null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (manager == null) {
            throw new SQLException("MariaDBBukkit is not running.");
        }
        return manager.getConnection();
    }

    @Override
    public String getJdbcUrl() {
        return manager != null ? manager.getJdbcUrl() : null;
    }

    @Override
    public int getPort() {
        return manager != null ? manager.getPort() : -1;
    }

    @Override
    public String getDatabaseName() {
        return getConfig().getString("database.name", "minecraft");
    }

    @Override
    public boolean isRunning() {
        return manager != null && manager.isRunning();
    }

    public synchronized void restart() throws Exception {
        if (manager != null) {
            manager.stop();
        }
        manager = buildAndStartManager();
    }
}
