package servidor.db.dao;

import java.sql.*;

public class OpcaoDAO {
    private final Connection connection;

    public OpcaoDAO(Connection connection) {
        this.connection = connection;
    }

    public void adicionar(int perguntaId, String letra, String texto, boolean correta) throws SQLException {
        String sql = "INSERT INTO Opcao (pergunta_id, letra, texto, is_correta) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, perguntaId);
            ps.setString(2, letra);
            ps.setString(3, texto);
            ps.setInt(4, correta ? 1 : 0);
            ps.executeUpdate();
        }
    }

    public void editar(int opcaoId, int perguntaId, String novoTexto, boolean novaCorreta) throws SQLException {
        // Verificar se pergunta tem respostas
        String sqlCheck = "SELECT COUNT(*) as total FROM Resposta WHERE pergunta_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sqlCheck)) {
            ps.setInt(1, perguntaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt("total") > 0) {
                    throw new SQLException("Não é possível editar opção: pergunta já tem respostas");
                }
            }
        }

        String sql = "UPDATE Opcao SET texto = ?, is_correta = ? WHERE id = ? AND pergunta_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, novoTexto);
            ps.setInt(2, novaCorreta ? 1 : 0);
            ps.setInt(3, opcaoId);
            ps.setInt(4, perguntaId);
            ps.executeUpdate();
        }
    }
}