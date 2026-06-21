package com.mariadbbukkit.domain.model.database;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

public interface DatabaseConnectionPool extends AutoCloseable {
    void init(String jdbcUrl, String username, String password, DatabaseConfig.Pool poolConfig, String driverClassName) throws Exception;
    DataSource getDataSource();
    Connection getConnection() throws SQLException;
    boolean isClosed();
    @Override
    void close();
}
