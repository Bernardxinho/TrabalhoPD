package servidor.db.util;

import java.sql.*;

public class SchemaManager {

    public static void createTables(Connection connection) throws SQLException {
        String configuracao = """
            CREATE TABLE IF NOT EXISTS Configuracao (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                versao INTEGER NOT NULL DEFAULT 0,
                codigo_registo_docentes TEXT NOT NULL
            );
            """;

        String docentes = """
            CREATE TABLE IF NOT EXISTS Docente (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                nome TEXT NOT NULL,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                data_criacao TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """;

        String estudantes = """
            CREATE TABLE IF NOT EXISTS Estudante (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                numero INTEGER UNIQUE NOT NULL,
                nome TEXT NOT NULL,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                data_criacao TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            );
            """;

        String perguntas = """
            CREATE TABLE IF NOT EXISTS Pergunta (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                enunciado TEXT NOT NULL,
                data_inicio TIMESTAMP NOT NULL,
                data_fim TIMESTAMP NOT NULL,
                codigo_acesso TEXT UNIQUE NOT NULL,
                docente_id INTEGER NOT NULL,
                data_criacao TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (docente_id) REFERENCES Docente(id)
            );
            """;

        String opcoes = """
            CREATE TABLE IF NOT EXISTS Opcao (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pergunta_id INTEGER NOT NULL,
                letra TEXT NOT NULL,
                texto TEXT NOT NULL,
                is_correta INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (pergunta_id) REFERENCES Pergunta(id) ON DELETE CASCADE,
                UNIQUE(pergunta_id, letra)
            );
            """;

        String respostas = """
            CREATE TABLE IF NOT EXISTS Resposta (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                estudante_id INTEGER NOT NULL,
                pergunta_id INTEGER NOT NULL,
                opcao_letra TEXT NOT NULL,
                data_hora TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (estudante_id) REFERENCES Estudante(id),
                FOREIGN KEY (pergunta_id) REFERENCES Pergunta(id),
                UNIQUE(estudante_id, pergunta_id)
            );
            """;

        Statement stmt = connection.createStatement();
        stmt.execute(configuracao);
        stmt.execute(docentes);
        stmt.execute(estudantes);
        stmt.execute(perguntas);
        stmt.execute(opcoes);
        stmt.execute(respostas);

        String checkConfig = "SELECT COUNT(*) FROM Configuracao";
        ResultSet rs = stmt.executeQuery(checkConfig);
        if (rs.next() && rs.getInt(1) == 0) {
            String codigoHash = SecurityUtil.hashPassword("DOCENTE2025");
            String insertConfig = "INSERT INTO Configuracao (id, versao, codigo_registo_docentes) VALUES (1, 0, ?)";
            PreparedStatement pstmt = connection.prepareStatement(insertConfig);
            pstmt.setString(1, codigoHash);
            pstmt.executeUpdate();
            pstmt.close();
            System.out.println("[SchemaManager] Configuração inicial criada (código: DOCENTE2025)");
        }

        stmt.close();
        System.out.println("[SchemaManager] Tabelas criadas/verificadas com sucesso!");
    }

    public static int getVersao(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT versao FROM Configuracao WHERE id = 1")) {
            if (rs.next()) {
                return rs.getInt("versao");
            }
        }
        return 0;
    }

    public static void incrementarVersao(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE Configuracao SET versao = versao + 1 WHERE id = 1");
        }
    }
}