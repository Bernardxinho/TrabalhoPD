package servidor.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private final String dbPath;
    private Connection connection;

    public DatabaseManager(String dbPath) {
        this.dbPath = dbPath;
    }

    public void connect() {
    try {
        Class.forName("org.sqlite.JDBC");

        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        System.out.println("[DB] Ligado a: " + dbPath);
    } catch (ClassNotFoundException e) {
        System.err.println("[DB] Driver SQLite não encontrado: " + e.getMessage());
    } catch (SQLException e) {
        System.err.println("[DB] Erro ao ligar: " + e.getMessage());
    }
}


    public void createTestTable() {
        String sql = "CREATE TABLE IF NOT EXISTS teste (id INTEGER PRIMARY KEY, nome TEXT);";
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
            System.out.println("[DB] Tabela de teste criada/verificada.");
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao criar tabela: " + e.getMessage());
        }
    }

    public void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao fechar ligação: " + e.getMessage());
        }
    }
}
