package servidor.db.dao;

import servidor.db.DatabaseManager;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class RespostaDAO {
    private final Connection connection;

    public RespostaDAO(Connection connection) {
        this.connection = connection;
    }

    public void guardar(int estudanteId, int perguntaId, String letra) throws SQLException {
        String sql = "INSERT INTO Resposta (estudante_id, pergunta_id, opcao_letra) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, estudanteId);
            ps.setInt(2, perguntaId);
            ps.setString(3, letra);
            ps.executeUpdate();
        }
    }

    public List<DatabaseManager.RespostaEstudanteInfo> listarRespostasEstudanteExpiradas(int estudanteId) throws SQLException {
        List<DatabaseManager.RespostaEstudanteInfo> lista = new ArrayList<>();

        String sql =
                "SELECT p.id            AS pergunta_id, " +
                        "       p.enunciado     AS enunciado, " +
                        "       p.data_fim      AS data_fim, " +
                        "       r.data_hora     AS data_hora, " +
                        "       r.opcao_letra   AS opcao_letra, " +
                        "       COALESCE(o.is_correta,0) AS correta " +
                        "FROM   Resposta r " +
                        "JOIN   Pergunta p ON p.id = r.pergunta_id " +
                        "LEFT JOIN Opcao o ON o.pergunta_id = r.pergunta_id AND o.letra = r.opcao_letra " +
                        "WHERE  r.estudante_id = ? " +
                        "  AND  p.data_fim < datetime('now') " +
                        "ORDER BY p.data_fim DESC, r.data_hora DESC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, estudanteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    DatabaseManager.RespostaEstudanteInfo info = new DatabaseManager.RespostaEstudanteInfo();
                    info.perguntaId = rs.getInt("pergunta_id");
                    info.enunciado = rs.getString("enunciado");
                    info.dataFim = rs.getString("data_fim");
                    info.dataResposta = rs.getString("data_hora");
                    info.letra = rs.getString("opcao_letra");
                    info.correta = rs.getInt("correta") == 1;
                    lista.add(info);
                }
            }
        }

        return lista;
    }
}