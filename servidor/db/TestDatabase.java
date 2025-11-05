package servidor.db;

import java.sql.*;

public class TestDatabase {

    public static void main(String[] args) {
        System.out.println("=== Teste da Base de Dados ===\n");

        DatabaseManager db = new DatabaseManager("servidor/sistema.db");
        db.connect();
        db.createTables();

        System.out.println("\n--- Informações da BD ---");
        System.out.println("Versão: " + db.getVersao());

     
        try {
            insertDadosTeste(db);
            listarTabelasComDados(db);
        } catch (SQLException e) {
            System.err.println("Erro nos testes: " + e.getMessage());
            e.printStackTrace();
        }

        db.close();
        System.out.println("\n=== Teste concluído ===");
    }

    private static void insertDadosTeste(DatabaseManager db) throws SQLException {
        Connection conn = db.getConnection();

        String hashDocente = DatabaseManager.hashPassword("senha123");
        PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO Docente (nome, email, password_hash) VALUES (?, ?, ?)"
        );
        ps.setString(1, "Prof. João Silva");
        ps.setString(2, "joao.silva@isec.pt");
        ps.setString(3, hashDocente);
        ps.executeUpdate();
        ps.close();

        String hashEstudante = DatabaseManager.hashPassword("senha456");
        ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO Estudante (numero, nome, email, password_hash) VALUES (?, ?, ?, ?)"
        );
        ps.setInt(1, 202412345);
        ps.setString(2, "Ana Costa");
        ps.setString(3, "ana.costa@isec.pt");
        ps.setString(4, hashEstudante);
        ps.executeUpdate();
        ps.close();

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT id FROM Docente WHERE email = 'joao.silva@isec.pt'");
        int docenteId = rs.getInt("id");
        rs.close();

        String codigoAcesso = DatabaseManager.gerarCodigoAcesso();
        ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO Pergunta (enunciado, data_inicio, data_fim, codigo_acesso, docente_id) " +
                        "VALUES (?, datetime('now'), datetime('now', '+1 day'), ?, ?)"
        );
        ps.setString(1, "Qual é a capital de Portugal?");
        ps.setString(2, codigoAcesso);
        ps.setInt(3, docenteId);
        ps.executeUpdate();
        ps.close();

        rs = stmt.executeQuery("SELECT id FROM Pergunta WHERE codigo_acesso = '" + codigoAcesso + "'");
        int perguntaId = rs.getInt("id");
        rs.close();

        ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO Opcao (pergunta_id, letra, texto, is_correta) VALUES (?, ?, ?, ?)"
        );

        ps.setInt(1, perguntaId);
        ps.setString(2, "a");
        ps.setString(3, "Lisboa");
        ps.setInt(4, 1); 
        ps.executeUpdate();

        ps.setInt(1, perguntaId);
        ps.setString(2, "b");
        ps.setString(3, "Porto");
        ps.setInt(4, 0);
        ps.executeUpdate();

        ps.setInt(1, perguntaId);
        ps.setString(2, "c");
        ps.setString(3, "Coimbra");
        ps.setInt(4, 0);
        ps.executeUpdate();

        ps.close();
        stmt.close();

        db.incrementarVersao();

        System.out.println("\n[TESTE] Dados de exemplo inseridos!");
        System.out.println("  - Docente: joao.silva@isec.pt / senha123");
        System.out.println("  - Estudante: ana.costa@isec.pt / senha456 (nº 202412345)");
        System.out.println("  - Pergunta criada com código: " + codigoAcesso);
    }

    private static void listarTabelasComDados(DatabaseManager db) throws SQLException {
        Connection conn = db.getConnection();
        Statement stmt = conn.createStatement();

        System.out.println("\n--- Contagem de registos ---");

        String[] tabelas = {"Configuracao", "Docente", "Estudante", "Pergunta", "Opcao", "Resposta"};

        for (String tabela : tabelas) {
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as total FROM " + tabela);
            if (rs.next()) {
                System.out.printf("  %-15s: %d registos\n", tabela, rs.getInt("total"));
            }
            rs.close();
        }

        stmt.close();
    }
}