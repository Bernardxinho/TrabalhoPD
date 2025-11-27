package servidor.db.dao;

import servidor.db.PerguntaDetalhes;
import servidor.db.util.SecurityUtil;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class PerguntaDAO {
    private final Connection connection;

    public PerguntaDAO(Connection connection) {
        this.connection = connection;
    }

    public static class PerguntaResult {
        public int id;
        public String codigoAcesso;

        public PerguntaResult(int id, String codigoAcesso) {
            this.id = id;
            this.codigoAcesso = codigoAcesso;
        }
    }

    public PerguntaResult criarCompleta(int docenteId, String enunciado, String dataInicio, String dataFim) throws SQLException {
        String codigo = SecurityUtil.gerarCodigoAcesso();
        String sql = "INSERT INTO Pergunta (enunciado, data_inicio, data_fim, codigo_acesso, docente_id) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, enunciado);
            ps.setString(2, dataInicio);
            ps.setString(3, dataFim);
            ps.setString(4, codigo);
            ps.setInt(5, docenteId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                int id = rs.next() ? rs.getInt(1) : -1;
                return new PerguntaResult(id, codigo);
            }
        }
    }

    public boolean pertenceADocente(int perguntaId, int docenteId) throws SQLException {
        String sql = "SELECT COUNT(*) as total FROM Pergunta WHERE id = ? AND docente_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, perguntaId);
            ps.setInt(2, docenteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("total") > 0;
            }
        }
    }

    public boolean temRespostas(int perguntaId) throws SQLException {
        String sql = "SELECT COUNT(*) as total FROM Resposta WHERE pergunta_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, perguntaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("total") > 0;
            }
        }
    }

    public void editar(int perguntaId, String novoEnunciado, String novaDataInicio, String novaDataFim) throws SQLException {
        if (temRespostas(perguntaId)) {
            throw new SQLException("Não é possível editar: pergunta já tem respostas");
        }

        String sql = "UPDATE Pergunta SET enunciado = ?, data_inicio = ?, data_fim = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, novoEnunciado);
            ps.setString(2, novaDataInicio);
            ps.setString(3, novaDataFim);
            ps.setInt(4, perguntaId);
            ps.executeUpdate();
        }
    }

    public void eliminar(int perguntaId) throws SQLException {
        if (temRespostas(perguntaId)) {
            throw new SQLException("Não é possível eliminar: pergunta já tem respostas");
        }

        // Eliminar opções primeiro
        String sqlOpcoes = "DELETE FROM Opcao WHERE pergunta_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sqlOpcoes)) {
            ps.setInt(1, perguntaId);
            ps.executeUpdate();
        }

        // Eliminar pergunta
        String sql = "DELETE FROM Pergunta WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, perguntaId);
            ps.executeUpdate();
        }
    }

    public List<PerguntaDetalhes> listar(int docenteId, String filtroEstado) throws SQLException {
        List<PerguntaDetalhes> lista = new ArrayList<>();

        String sql =
                "SELECT p.id, p.enunciado, p.data_inicio, p.data_fim, p.codigo_acesso, " +
                        "       p.docente_id, p.data_criacao, " +
                        "       (SELECT COUNT(*) FROM Resposta r WHERE r.pergunta_id = p.id) AS num_respostas, " +
                        "       (SELECT COUNT(*) FROM Opcao   o WHERE o.pergunta_id = p.id) AS num_opcoes " +
                        "FROM Pergunta p WHERE p.docente_id = ? ";

        if (filtroEstado != null) {
            switch (filtroEstado.toUpperCase()) {
                case "ATIVA":
                    sql += "AND datetime('now') BETWEEN p.data_inicio AND p.data_fim " +
                            "AND (SELECT COUNT(*) FROM Opcao o WHERE o.pergunta_id = p.id) >= 2 ";
                    break;
                case "FUTURA":
                    sql += "AND datetime('now') < p.data_inicio ";
                    break;
                case "EXPIRADA":
                    sql += "AND datetime('now') > p.data_fim ";
                    break;
            }
        }

        sql += "ORDER BY p.data_inicio DESC";

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, docenteId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PerguntaDetalhes pd = new PerguntaDetalhes();
                    pd.id = rs.getInt("id");
                    pd.enunciado = rs.getString("enunciado");
                    pd.dataInicio = rs.getString("data_inicio");
                    pd.dataFim = rs.getString("data_fim");
                    pd.codigoAcesso = rs.getString("codigo_acesso");
                    pd.docenteId = rs.getInt("docente_id");
                    pd.dataCriacao = rs.getString("data_criacao");
                    pd.numRespostas = rs.getInt("num_respostas");
                    int numOpcoes = rs.getInt("num_opcoes");

                    pd.estado = calcularEstado(pd.dataInicio, pd.dataFim, numOpcoes);
                    lista.add(pd);
                }
            }
        }

        return lista;
    }

    private String calcularEstado(String dataInicio, String dataFim, int numOpcoes) throws SQLException {
        String sqlEstado = "SELECT CASE " +
                "WHEN datetime('now') < ? THEN 'FUTURA' " +
                "WHEN datetime('now') > ? THEN 'EXPIRADA' " +
                "ELSE 'ATIVA' END AS estado";
        try (PreparedStatement psEstado = connection.prepareStatement(sqlEstado)) {
            psEstado.setString(1, dataInicio);
            psEstado.setString(2, dataFim);
            try (ResultSet rsEstado = psEstado.executeQuery()) {
                String estado = rsEstado.next() ? rsEstado.getString("estado") : "DESCONHECIDO";
                if ("ATIVA".equals(estado) && numOpcoes < 2) {
                    estado = "FUTURA";
                }
                return estado;
            }
        }
    }

    public PerguntaDetalhes obterPorCodigo(String codigo) throws SQLException {
        PerguntaDetalhes pd = null;

        String sqlPergunta = "SELECT * FROM Pergunta WHERE codigo_acesso = ?";
        try (PreparedStatement ps = connection.prepareStatement(sqlPergunta)) {
            ps.setString(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                pd = new PerguntaDetalhes();
                pd.id = rs.getInt("id");
                pd.enunciado = rs.getString("enunciado");
                pd.dataInicio = rs.getString("data_inicio");
                pd.dataFim = rs.getString("data_fim");
                pd.codigoAcesso = rs.getString("codigo_acesso");
                pd.docenteId = rs.getInt("docente_id");
                pd.dataCriacao = rs.getString("data_criacao");
            }
        }

        pd.estado = calcularEstado(pd.dataInicio, pd.dataFim, 0);

        // Carregar opções
        String sqlOpcoes = "SELECT * FROM Opcao WHERE pergunta_id = ? ORDER BY letra";
        try (PreparedStatement ps = connection.prepareStatement(sqlOpcoes)) {
            ps.setInt(1, pd.id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PerguntaDetalhes.OpcaoDetalhes od = new PerguntaDetalhes.OpcaoDetalhes(
                            rs.getInt("id"),
                            rs.getString("letra"),
                            rs.getString("texto"),
                            rs.getInt("is_correta") == 1
                    );
                    pd.opcoes.add(od);
                }
            }
        }

        return pd;
    }

    public PerguntaDetalhes obterDetalhesExpirada(int perguntaId, int docenteId) throws SQLException {
        if (!pertenceADocente(perguntaId, docenteId)) {
            throw new SQLException("Pergunta não pertence ao docente");
        }

        PerguntaDetalhes pd = new PerguntaDetalhes();

        String sqlPergunta = "SELECT * FROM Pergunta WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sqlPergunta)) {
            ps.setInt(1, perguntaId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("Pergunta não encontrada");
                }

                pd.id = rs.getInt("id");
                pd.enunciado = rs.getString("enunciado");
                pd.dataInicio = rs.getString("data_inicio");
                pd.dataFim = rs.getString("data_fim");
                pd.codigoAcesso = rs.getString("codigo_acesso");
                pd.docenteId = rs.getInt("docente_id");
                pd.dataCriacao = rs.getString("data_criacao");

                String sqlEstado = "SELECT CASE WHEN datetime('now') > ? THEN 1 ELSE 0 END as expirada";
                try (PreparedStatement psEstado = connection.prepareStatement(sqlEstado)) {
                    psEstado.setString(1, pd.dataFim);
                    try (ResultSet rsEstado = psEstado.executeQuery()) {
                        if (rsEstado.next() && rsEstado.getInt("expirada") == 0) {
                            throw new SQLException("Pergunta ainda não expirou");
                        }
                    }
                }
                pd.estado = "EXPIRADA";
            }
        }

        // Carregar opções com contagens
        String sqlOpcoes = "SELECT * FROM Opcao WHERE pergunta_id = ? ORDER BY letra";
        try (PreparedStatement ps = connection.prepareStatement(sqlOpcoes)) {
            ps.setInt(1, perguntaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PerguntaDetalhes.OpcaoDetalhes od = new PerguntaDetalhes.OpcaoDetalhes(
                            rs.getInt("id"),
                            rs.getString("letra"),
                            rs.getString("texto"),
                            rs.getInt("is_correta") == 1
                    );

                    String sqlCount = "SELECT COUNT(*) as total FROM Resposta WHERE pergunta_id = ? AND opcao_letra = ?";
                    try (PreparedStatement psCount = connection.prepareStatement(sqlCount)) {
                        psCount.setInt(1, perguntaId);
                        psCount.setString(2, od.letra);
                        try (ResultSet rsCount = psCount.executeQuery()) {
                            if (rsCount.next()) {
                                od.numRespostas = rsCount.getInt("total");
                            }
                        }
                    }

                    pd.opcoes.add(od);
                }
            }
        }

        // Carregar respostas
        String sqlRespostas = "SELECT r.opcao_letra, r.data_hora, " +
                "e.id as est_id, e.numero, e.nome, e.email, " +
                "o.is_correta " +
                "FROM Resposta r " +
                "JOIN Estudante e ON r.estudante_id = e.id " +
                "LEFT JOIN Opcao o ON o.pergunta_id = r.pergunta_id AND o.letra = r.opcao_letra " +
                "WHERE r.pergunta_id = ? " +
                "ORDER BY e.numero";

        try (PreparedStatement ps = connection.prepareStatement(sqlRespostas)) {
            ps.setInt(1, perguntaId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PerguntaDetalhes.RespostaDetalhes rd = new PerguntaDetalhes.RespostaDetalhes(
                            rs.getInt("est_id"),
                            rs.getInt("numero"),
                            rs.getString("nome"),
                            rs.getString("email"),
                            rs.getString("opcao_letra"),
                            rs.getString("data_hora"),
                            rs.getInt("is_correta") == 1
                    );
                    pd.respostas.add(rd);
                }
            }
        }

        pd.numRespostas = pd.respostas.size();

        return pd;
    }

    public String exportarParaCSV(int perguntaId, int docenteId) throws SQLException {
        PerguntaDetalhes pd = obterDetalhesExpirada(perguntaId, docenteId);

        StringBuilder csv = new StringBuilder();

        csv.append("\"dia\";\"hora inicial\";\"hora final\";\"enunciado da pergunta\";\"opção certa\"\n");

        String dia = pd.dataInicio.substring(0, 10);
        String horaInicial = pd.dataInicio.substring(11, 16);
        String horaFinal = pd.dataFim.substring(11, 16);

        String letraCorreta = "";
        for (PerguntaDetalhes.OpcaoDetalhes op : pd.opcoes) {
            if (op.isCorreta) {
                letraCorreta = op.letra;
                break;
            }
        }

        csv.append(String.format("\"%s\";\"%s\";\"%s\";\"%s\";\"%s\"\n",
                dia, horaInicial, horaFinal,
                pd.enunciado.replace("\"", "\"\""),
                letraCorreta));

        csv.append("\n\"opção\";\"texto da opção\"\n");
        for (PerguntaDetalhes.OpcaoDetalhes op : pd.opcoes) {
            csv.append(String.format("\"%s\";\"%s\"\n",
                    op.letra,
                    op.texto.replace("\"", "\"\"")));
        }

        csv.append("\n\"número de estudante\";\"nome\";\"e-mail\";\"resposta\"\n");
        for (PerguntaDetalhes.RespostaDetalhes resp : pd.respostas) {
            csv.append(String.format("\"%d\";\"%s\";\"%s\";\"%s\"\n",
                    resp.estudanteNumero,
                    resp.estudanteNome.replace("\"", "\"\""),
                    resp.estudanteEmail,
                    resp.opcaoLetra));
        }
        return csv.toString();
    }
}