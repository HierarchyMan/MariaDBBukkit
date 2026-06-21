package com.mariadbbukkit.infrastructure.database.mariadb;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.exec.ManagedProcessException;
import com.mariadbbukkit.domain.model.database.DatabaseConfig;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class MariaDBUserInitializer {

    public static void initialize(Logger log, DB db, DatabaseConfig config) {
        String dbName = config.getCredentials().getDbName();
        try {
            db.createDB(dbName);
        } catch (ManagedProcessException e) {
            log.log(Level.WARNING, "Failed to create database " + dbName, e);
            return;
        }

        String username = config.getCredentials().getUsername();
        String password = config.getCredentials().getPassword();

        if (config.getSecurity().isSkipGrantTables()) {
            log.info("Database '" + dbName + "' ready (skip-grant-tables: any"
                    + " credentials accepted, user '" + username + "' will work over JDBC).");
        } else {
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
}
