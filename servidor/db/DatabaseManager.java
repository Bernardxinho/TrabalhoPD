package servidor.db;

import servidor.db.dao.*;
import servidor.db.util.SchemaManager;
import servidor.db.util.SecurityUtil;
import java.sql.*;
import java.util.List;

/**
 * Facade para acesso à base de dados.
 */
public class DatabaseManager {
    private final DatabaseConnection dbConnection;

    public DatabaseManager(String dbPath) {
        this.dbConnection = new DatabaseConnection(dbPath);
    }

    public void connect() {
        dbConnection.connect();
    }

    public void createTables() {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            SchemaManager.createTables(conn);
        } catch (Exception e) {
            System.err.println("[DB] Erro ao criar tabelas: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized int getVersao() {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            return SchemaManager.getVersao(conn);
        } catch (Exception e) {
            System.err.println("[DB] Erro ao obter versão: " + e.getMessage());
            e.printStackTrace();
            return 0;
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void incrementarVersao() {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            SchemaManager.incrementarVersao(conn);
            System.out.println("[DB] Versão incrementada para: " + getVersao());
        } catch (Exception e) {
            System.err.println("[DB] Erro ao incrementar versão: " + e.getMessage());
            e.printStackTrace();
        } finally {
            closeQuietly(conn);
        }
    }

    public Connection getConnection() {
        return dbConnection.getConnection();
    }

    public void close() {
        dbConnection.close();
    }

    private void closeQuietly(Connection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e) {
                System.err.println("[DB] Erro ao fechar conexão: " + e.getMessage());
            }
        }
    }

    public static String hashPassword(String password) {
        return SecurityUtil.hashPassword(password);
    }

    public static String gerarCodigoAcesso() {
        return SecurityUtil.gerarCodigoAcesso();
    }

    public synchronized boolean autenticarDocente(String email, String password) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            DocenteDAO dao = new DocenteDAO(conn);
            return dao.autenticar(email, password);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized int getDocenteId(String email) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            DocenteDAO dao = new DocenteDAO(conn);
            return dao.getId(email);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized boolean validarCodigoDocente(String codigoClaro) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            DocenteDAO dao = new DocenteDAO(conn);
            return dao.validarCodigoRegistro(codigoClaro);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized int criarDocente(String nome, String email, String passwordClaro) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            DocenteDAO dao = new DocenteDAO(conn);
            return dao.criar(nome, email, passwordClaro);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void atualizarDocentePerfil(int docenteId, String novoNome, String novoEmail, String novaPasswordClaro) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            DocenteDAO dao = new DocenteDAO(conn);
            dao.atualizarPerfil(docenteId, novoNome, novoEmail, novaPasswordClaro);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized boolean autenticarEstudante(String email, String password) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            EstudanteDAO dao = new EstudanteDAO(conn);
            return dao.autenticar(email, password);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized int criarEstudante(int numero, String nome, String email, String passwordClaro) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            EstudanteDAO dao = new EstudanteDAO(conn);
            return dao.criar(numero, nome, email, passwordClaro);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void atualizarEstudantePerfil(int estudanteId, String novoNome, String novoEmail, String novaPass) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            EstudanteDAO dao = new EstudanteDAO(conn);
            dao.atualizarPerfil(estudanteId, novoNome, novoEmail, novaPass);
        } finally {
            closeQuietly(conn);
        }
    }

    public static class PerguntaResult {
        public int id;
        public String codigoAcesso;

        public PerguntaResult(int id, String codigoAcesso) {
            this.id = id;
            this.codigoAcesso = codigoAcesso;
        }
    }

    public synchronized PerguntaResult criarPerguntaCompleta(int docenteId, String enunciado, String dataInicio, String dataFim) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            PerguntaDAO.PerguntaResult result = dao.criarCompleta(docenteId, enunciado, dataInicio, dataFim);
            return new PerguntaResult(result.id, result.codigoAcesso);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized boolean perguntaPertenceADocente(int perguntaId, int docenteId) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            return dao.pertenceADocente(perguntaId, docenteId);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized boolean perguntaTemRespostas(int perguntaId) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            return dao.temRespostas(perguntaId);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void editarPergunta(int perguntaId, String novoEnunciado, String novaDataInicio, String novaDataFim) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            dao.editar(perguntaId, novoEnunciado, novaDataInicio, novaDataFim);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void eliminarPergunta(int perguntaId) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            dao.eliminar(perguntaId);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized List<PerguntaDetalhes> listarPerguntas(int docenteId, String filtroEstado) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            return dao.listar(docenteId, filtroEstado);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized PerguntaDetalhes obterPerguntaAtivaPorCodigo(String codigo) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            return dao.obterPorCodigo(codigo);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized PerguntaDetalhes obterDetalhesPerguntaExpirada(int perguntaId, int docenteId) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            return dao.obterDetalhesExpirada(perguntaId, docenteId);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized String exportarParaCSV(int perguntaId, int docenteId) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            PerguntaDAO dao = new PerguntaDAO(conn);
            return dao.exportarParaCSV(perguntaId, docenteId);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void adicionarOpcao(int perguntaId, String letra, String texto, boolean correta) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            OpcaoDAO dao = new OpcaoDAO(conn);
            dao.adicionar(perguntaId, letra, texto, correta);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void editarOpcao(int opcaoId, int perguntaId, String novoTexto, boolean novaCorreta) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            OpcaoDAO dao = new OpcaoDAO(conn);
            dao.editar(opcaoId, perguntaId, novoTexto, novaCorreta);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void guardarResposta(int estudanteId, int perguntaId, String letra) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            RespostaDAO dao = new RespostaDAO(conn);
            dao.guardar(estudanteId, perguntaId, letra);
        } finally {
            closeQuietly(conn);
        }
    }

    public static class RespostaEstudanteInfo {
        public int perguntaId;
        public String enunciado;
        public String dataFim;
        public String dataResposta;
        public String letra;
        public boolean correta;
    }

    public synchronized List<RespostaEstudanteInfo> listarRespostasEstudanteExpiradas(int estudanteId) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            RespostaDAO dao = new RespostaDAO(conn);
            return dao.listarRespostasEstudanteExpiradas(estudanteId);
        } finally {
            closeQuietly(conn);
        }
    }

    public synchronized void executarQuery(String sql) throws SQLException {
        Connection conn = null;
        try {
            conn = dbConnection.getConnection();
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate(sql);
            }
        } finally {
            closeQuietly(conn);
        }
    }
}