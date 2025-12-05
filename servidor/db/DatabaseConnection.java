package servidor.db;

import java.sql.*;

public class DatabaseConnection {
    private final String dbPath;
    private Connection connection;
    private final Object lock = new Object();

    public DatabaseConnection(String dbPath) {
        this.dbPath = dbPath;
    }

    public void connect() {
        synchronized (lock) {
            try {
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                connection.setAutoCommit(true);

                Statement stmt = connection.createStatement();
                stmt.execute("PRAGMA journal_mode=WAL;");
                stmt.execute("PRAGMA synchronous=NORMAL;");
                stmt.execute("PRAGMA busy_timeout=5000;");
                stmt.close();

            } catch (ClassNotFoundException e) {
                System.err.println("[DB] Driver SQLite não encontrado: " + e.getMessage());
            } catch (SQLException e) {
                System.err.println("[DB] Erro ao ligar: " + e.getMessage());
            }
        }
    }

    public Connection getConnection() {
        synchronized (lock) {
            try {
                if (connection == null || connection.isClosed()) {
                    connect();
                }
            } catch (SQLException e) {
                System.err.println("[DB] Erro ao verificar conexão: " + e.getMessage());
                try {
                    connect();
                } catch (Exception ex) {
                    System.err.println("[DB] Falha ao reconectar: " + ex.getMessage());
                }
            }
            return connection;
        }
    }

    public void close() {
        synchronized (lock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                System.err.println("[DB] Erro ao fechar ligação: " + e.getMessage());
            }
        }
    }
}