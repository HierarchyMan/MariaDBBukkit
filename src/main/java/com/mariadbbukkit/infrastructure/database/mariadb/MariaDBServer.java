package com.mariadbbukkit.infrastructure.database.mariadb;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfiguration;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import ch.vorburger.exec.ManagedProcessException;
import com.mariadbbukkit.domain.model.database.DatabaseConfig;
import com.mariadbbukkit.domain.model.database.DatabaseServer;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class MariaDBServer implements DatabaseServer {

    private final Logger log;
    private DBConfigurationBuilder builder;
    private DB db;
    private int actualPort = -1;

    public MariaDBServer(Logger log) {
        this.log = log;
    }

    @Override
    public void start(DatabaseConfig config) throws Exception {
        var platformDetector = new com.mariadbbukkit.infrastructure.platform.SystemPlatformDetector();
        var platform = platformDetector.current();
        String termuxPrefix = config.getTermux().getPrefix();
        log.info("Starting embedded MariaDB (platform=" + platform
                + (termuxPrefix != null ? ", termux mode" : "") + ")...");

        builder = DBConfigurationBuilder.newBuilder();
        builder.setPort(config.getPort().getValue());
        builder.setBaseDir(config.getStorage().getBaseDir().toFile());
        builder.setLibDir(config.getStorage().getBaseDir().resolve("lib").toFile());
        builder.setDataDir(config.getStorage().getDataDir().toFile());
        builder.setTmpDir(config.getStorage().getTmpDir().toAbsolutePath().toString());
        builder.setUnpackingFromClasspath(false);
        builder.setSecurityDisabled(config.getSecurity().isSkipGrantTables());
        builder.setDeletingTemporaryBaseAndDataDirsOnShutdown(false);

        if (termuxPrefix != null) {
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

        DBConfiguration dbConfig = builder.build();
        actualPort = dbConfig.getPort();

        db = DB.newEmbeddedDB(dbConfig);
        db.start();
        log.info("MariaDB is up on port " + actualPort + " (data: "
                + config.getStorage().getDataDir().toAbsolutePath() + ")");

        // Provision schema/user
        MariaDBUserInitializer.initialize(log, db, config);
    }

    @Override
    public void stop() {
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

    @Override
    public boolean isRunning() {
        return db != null;
    }

    @Override
    public int getPort() {
        return actualPort;
    }

    @Override
    public String getJdbcUrl(String dbName) {
        return builder != null ? builder.getURL(dbName) : null;
    }
}
