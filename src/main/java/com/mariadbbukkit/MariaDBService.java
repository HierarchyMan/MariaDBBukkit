package com.mariadbbukkit;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Public service other Bukkit plugins can use to access the embedded MariaDB
 * managed by MariaDBBukkit.
 *
 * <p>Usage from another plugin:
 * <pre>{@code
 * var reg = Bukkit.getServicesManager().getRegistration(MariaDBService.class);
 * if (reg != null) {
 *     MariaDBService svc = reg.getProvider();
 *     try (Connection c = svc.getConnection()) {
 *         // use c
 *     }
 * }
 * }</pre>
 */
public interface MariaDBService {

    /** A live HikariCP-backed DataSource. Never returns null once started. */
    DataSource getDataSource();

    /** Borrows a connection from the pool. Caller must close it. */
    Connection getConnection() throws SQLException;

    /** JDBC url other plugins can use to connect directly. */
    String getJdbcUrl();

    /** The actual TCP port MariaDB is listening on (useful when configured as 0). */
    int getPort();

    /** The database/schema name other plugins should use. */
    String getDatabaseName();

    /** Whether the server is currently up and the pool is open. */
    boolean isRunning();
}
