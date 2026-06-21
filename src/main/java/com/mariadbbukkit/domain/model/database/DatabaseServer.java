package com.mariadbbukkit.domain.model.database;

public interface DatabaseServer {
    void start(DatabaseConfig config) throws Exception;
    void stop();
    boolean isRunning();
    int getPort();
    String getJdbcUrl(String dbName);
}
