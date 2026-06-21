package com.mariadbbukkit.presentation.bukkit;

import com.mariadbbukkit.MariaDBService;
import com.mariadbbukkit.application.service.DatabaseApplicationService;
import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.database.DatabaseType;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Level;

public final class MariaDBBukkitPlugin extends JavaPlugin implements MariaDBService {

    private DatabaseApplicationService appService;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        getLogger().info("MariaDBBukkit loading. Starting database...");
        appService = new DatabaseApplicationService(getLogger());
        try {
            DatabaseConfig dbConfig = buildConfig();
            appService.start(dbConfig);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to start embedded MariaDB during onLoad.", e);
            appService.stop();
            appService = null;
        }
    }

    @Override
    public void onEnable() {
        if (appService == null || !appService.isRunning()) {
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

    private DatabaseConfig buildConfig() {
        var dataFolder = getDataFolder().toPath();
        Path baseDir = resolvePath(dataFolder, getConfig().getString("storage.base-dir", "mariadb"));
        Path dataDir = resolvePath(dataFolder, getConfig().getString("storage.data-dir", "data"));
        Path tmpDir = resolvePath(dataFolder, getConfig().getString("storage.tmp-dir", "tmp"));

        String termuxPrefix = appService.getPlatformDetector().termuxPrefix();

        if (termuxPrefix != null) {
            baseDir = Path.of(termuxPrefix);
        }

        var port = new DatabaseConfig.Port(getConfig().getInt("database.port", 3306));
        var credentials = new DatabaseConfig.Credentials(
                getConfig().getString("database.name", "minecraft"),
                getConfig().getString("database.username", "minecraft"),
                getConfig().getString("database.password", "")
        );
        var storage = new DatabaseConfig.Storage(baseDir, dataDir, tmpDir);
        var pool = new DatabaseConfig.Pool(
                getConfig().getInt("pool.maximum-pool-size", 10),
                getConfig().getInt("pool.minimum-idle", 2),
                getConfig().getLong("pool.connection-timeout-ms", 30000L),
                getConfig().getLong("pool.idle-timeout-ms", 600000L),
                getConfig().getLong("pool.max-lifetime-ms", 1800000L)
        );
        var security = new DatabaseConfig.Security(getConfig().getBoolean("security.skip-grant-tables", true));
        var termux = new DatabaseConfig.Termux(getConfig().getBoolean("termux.auto-install", true), termuxPrefix);
        var download = new DatabaseConfig.Download(
                getConfig().getBoolean("download.enabled", true),
                getConfig().getString("download.mariadb-version", "11.4.5"),
                getConfig().getString("download.source-url", "")
        );

        return new DatabaseConfig(
                DatabaseType.MARIADB,
                port,
                credentials,
                storage,
                pool,
                security,
                termux,
                download
        );
    }

    private Path resolvePath(Path dataFolder, String configured) {
        Path p = Path.of(configured);
        return p.isAbsolute() ? p : dataFolder.resolve(p);
    }

    @Override
    public void onDisable() {
        if (appService != null) {
            appService.stop();
            appService = null;
        }
        if (getServer() != null) {
            getServer().getServicesManager().unregister(MariaDBService.class, this);
        }
    }

    public DatabaseApplicationService getAppService() {
        return appService;
    }

    // ---- MariaDBService ----

    @Override
    public DataSource getDataSource() {
        return appService != null ? appService.getDataSource() : null;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (appService == null) {
            throw new SQLException("MariaDBBukkit is not running.");
        }
        return appService.getConnection();
    }

    @Override
    public String getJdbcUrl() {
        return appService != null ? appService.getJdbcUrl(getDatabaseName()) : null;
    }

    @Override
    public int getPort() {
        return appService != null ? appService.getPort() : -1;
    }

    @Override
    public String getDatabaseName() {
        return getConfig().getString("database.name", "minecraft");
    }

    @Override
    public boolean isRunning() {
        return appService != null && appService.isRunning();
    }

    public synchronized void restart() throws Exception {
        if (appService != null) {
            appService.stop();
        }
        // reload configuration
        reloadConfig();
        appService = new DatabaseApplicationService(getLogger());
        DatabaseConfig dbConfig = buildConfig();
        appService.start(dbConfig);
    }
}
