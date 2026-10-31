package com.discordmini.server.dao;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Delegate to central config DatabaseConnection.
 */
public class DatabaseConnection {

    public static Connection getConnection() throws SQLException {
        return com.discordmini.server.config.DatabaseConnection.getConnection();
    }
}
