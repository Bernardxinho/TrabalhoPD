package servidor.db;

import java.sql.*;

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
            connection.setAutoCommit(true);

            // Configurações importantes para SQLite com múltiplas threads
            Statement stmt = connection.createStatement();
            stmt.execute("PRAGMA journal_mode=WAL;"); // Write-Ahead Logging para melhor concorrência
            stmt.execute("PRAGMA synchronous=NORMAL;");
            stmt.close();

            System.out.println("[DB] Ligado a: " + dbPath);
        } catch (ClassNotFoundException e) {
            System.err.println("[DB] Driver SQLite não encontrado: " + e.getMessage());
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao ligar: " + e.getMessage());
        }
    }

    public void createTables() {
        try {
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
                String codigoHash = hashPassword("DOCENTE2025");
                String insertConfig = "INSERT INTO Configuracao (id, versao, codigo_registo_docentes) VALUES (1, 0, ?)";
                PreparedStatement pstmt = connection.prepareStatement(insertConfig);
                pstmt.setString(1, codigoHash);
                pstmt.executeUpdate();
                pstmt.close();
                System.out.println("[DB] Configuração inicial criada (código: DOCENTE2025)");
            }

            stmt.close();
            System.out.println("[DB] Todas as tabelas criadas/verificadas com sucesso!");

        } catch (SQLException e) {
            System.err.println("[DB] Erro ao criar tabelas: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Método sincronizado para thread-safety
    public synchronized int getVersao() {
        try {
            // Verificar se a conexão está fechada
            if (connection == null || connection.isClosed()) {
                System.err.println("[DB] Conexão fechada, a reconectar...");
                connect();
            }

            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT versao FROM Configuracao WHERE id = 1");
            if (rs.next()) {
                int versao = rs.getInt("versao");
                rs.close();
                stmt.close();
                return versao;
            }
            stmt.close();
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao obter versão: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

    // Método sincronizado para thread-safety
    public synchronized void incrementarVersao() {
        try {
            // Verificar se a conexão está fechada
            if (connection == null || connection.isClosed()) {
                System.err.println("[DB] Conexão fechada, a reconectar...");
                connect();
            }

            Statement stmt = connection.createStatement();
            stmt.executeUpdate("UPDATE Configuracao SET versao = versao + 1 WHERE id = 1");
            stmt.close();
            System.out.println("[DB] Versão incrementada para: " + getVersao());
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao incrementar versão: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static String hashPassword(String password) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            System.err.println("[DB] Erro ao gerar hash: " + e.getMessage());
            return null;
        }
    }

    public static String gerarCodigoAcesso() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder codigo = new StringBuilder();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 8; i++) {
            codigo.append(chars.charAt(random.nextInt(chars.length())));
        }
        return codigo.toString();
    }

    public Connection getConnection() {
        try {
            // Verificar se a conexão está fechada e reconectar se necessário
            if (connection == null || connection.isClosed()) {
                System.err.println("[DB] Conexão fechada, a reconectar...");
                connect();
            }
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao verificar conexão: " + e.getMessage());
        }
        return connection;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("[DB] Conexão fechada.");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao fechar ligação: " + e.getMessage());
        }
    }

    public synchronized boolean autenticarDocente(String email, String password) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        String sql = "SELECT password_hash FROM Docente WHERE email = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, email);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            String hash = rs.getString("password_hash");
            ps.close();
            return hash.equals(hashPassword(password));
        }
        ps.close();
        return false;
    }

    public synchronized boolean autenticarEstudante(String email, String password) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        String sql = "SELECT password_hash FROM Estudante WHERE email = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, email);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            String hash = rs.getString("password_hash");
            ps.close();
            return hash.equals(hashPassword(password));
        }
        ps.close();
        return false;
    }

    // Classe interna para retornar resultado completo
    public static class PerguntaResult {
        public int id;
        public String codigoAcesso;

        public PerguntaResult(int id, String codigoAcesso) {
            this.id = id;
            this.codigoAcesso = codigoAcesso;
        }
    }

    public synchronized PerguntaResult criarPerguntaCompleta(int docenteId, String enunciado, String dataInicio, String dataFim) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        String codigo = gerarCodigoAcesso();
        String sql = "INSERT INTO Pergunta (enunciado, data_inicio, data_fim, codigo_acesso, docente_id) VALUES (?, ?, ?, ?, ?)";
        PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        ps.setString(1, enunciado);
        ps.setString(2, dataInicio);
        ps.setString(3, dataFim);
        ps.setString(4, codigo);
        ps.setInt(5, docenteId);
        ps.executeUpdate();
        ResultSet rs = ps.getGeneratedKeys();
        int id = rs.next() ? rs.getInt(1) : -1;
        ps.close();
        return new PerguntaResult(id, codigo);
    }

    public synchronized void adicionarOpcao(int perguntaId, String letra, String texto, boolean correta) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        String sql = "INSERT INTO Opcao (pergunta_id, letra, texto, is_correta) VALUES (?, ?, ?, ?)";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setInt(1, perguntaId);
        ps.setString(2, letra);
        ps.setString(3, texto);
        ps.setInt(4, correta ? 1 : 0);
        ps.executeUpdate();
        ps.close();
    }

    public synchronized void guardarResposta(int estudanteId, int perguntaId, String letra) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        String sql = "INSERT OR REPLACE INTO Resposta (estudante_id, pergunta_id, opcao_letra) VALUES (?, ?, ?)";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setInt(1, estudanteId);
        ps.setInt(2, perguntaId);
        ps.setString(3, letra);
        ps.executeUpdate();
        ps.close();
    }

    public synchronized int getDocenteId(String email) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        String sql = "SELECT id FROM Docente WHERE email = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, email);
        ResultSet rs = ps.executeQuery();
        int id = rs.next() ? rs.getInt("id") : -1;
        ps.close();
        return id;
    }

    public synchronized boolean validarCodigoDocente(String codigoClaro) throws SQLException {
        if (connection == null || connection.isClosed()) connect();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT codigo_registo_docentes FROM Configuracao WHERE id=1")) {
            if (!rs.next()) return false;
            String hashBD = rs.getString(1);
            String hashIntroduzido = hashPassword(codigoClaro);
            return hashBD != null && hashBD.equals(hashIntroduzido);
        }
    }

    public synchronized int criarDocente(String nome, String email, String passwordClaro) throws SQLException {
        if (connection == null || connection.isClosed()) connect();
        String sql = "INSERT INTO Docente (nome, email, password_hash) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setString(3, hashPassword(passwordClaro));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    public synchronized int criarEstudante(int numero, String nome, String email, String passwordClaro) throws SQLException {
        if (connection == null || connection.isClosed()) connect();
        String sql = "INSERT INTO Estudante (numero, nome, email, password_hash) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, numero);
            ps.setString(2, nome);
            ps.setString(3, email);
            ps.setString(4, hashPassword(passwordClaro));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    public synchronized void executarQuery(String sql) throws SQLException {
        if (connection == null || connection.isClosed()) {
            connect();
        }
        Statement stmt = connection.createStatement();
        stmt.executeUpdate(sql);
        stmt.close();
    }

    // ===== FASE 2: FUNCIONALIDADES DO DOCENTE =====

    /**
     * Lista perguntas de um docente com filtros opcionais
     * @param docenteId ID do docente
     * @param filtroEstado null, "ATIVA", "FUTURA", "EXPIRADA"
     * @return Lista de perguntas resumidas
     */
    public synchronized java.util.List<PerguntaDetalhes> listarPerguntas(int docenteId, String filtroEstado) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        java.util.List<PerguntaDetalhes> lista = new java.util.ArrayList<>();

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
                    pd.id           = rs.getInt("id");
                    pd.enunciado    = rs.getString("enunciado");
                    pd.dataInicio   = rs.getString("data_inicio");
                    pd.dataFim      = rs.getString("data_fim");
                    pd.codigoAcesso = rs.getString("codigo_acesso");
                    pd.docenteId    = rs.getInt("docente_id");
                    pd.dataCriacao  = rs.getString("data_criacao");
                    pd.numRespostas = rs.getInt("num_respostas");
                    int numOpcoes   = rs.getInt("num_opcoes");

                    String sqlEstado = "SELECT CASE " +
                            "WHEN datetime('now') < ? THEN 'FUTURA' " +
                            "WHEN datetime('now') > ? THEN 'EXPIRADA' " +
                            "ELSE 'ATIVA' END AS estado";
                    try (PreparedStatement psEstado = connection.prepareStatement(sqlEstado)) {
                        psEstado.setString(1, pd.dataInicio);
                        psEstado.setString(2, pd.dataFim);
                        try (ResultSet rsEstado = psEstado.executeQuery()) {
                            pd.estado = rsEstado.next() ? rsEstado.getString("estado") : "DESCONHECIDO";
                        }
                    }

                    if ("ATIVA".equals(pd.estado) && numOpcoes < 2) {
                        pd.estado = "FUTURA";
                    }

                    lista.add(pd);
                }
            }
        }

        return lista;
    }

    /**
     * Verifica se uma pergunta tem respostas associadas
     */
    public synchronized boolean perguntaTemRespostas(int perguntaId) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        String sql = "SELECT COUNT(*) as total FROM Resposta WHERE pergunta_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, perguntaId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("total") > 0;
            }
        }
    }

    /**
     * Verifica se uma pergunta pertence a um docente
     */
    public synchronized boolean perguntaPertenceADocente(int perguntaId, int docenteId) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        String sql = "SELECT COUNT(*) as total FROM Pergunta WHERE id = ? AND docente_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, perguntaId);
            ps.setInt(2, docenteId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("total") > 0;
            }
        }
    }

    /**
     * Edita uma pergunta (apenas se não tiver respostas)
     */
    public synchronized void editarPergunta(int perguntaId, String novoEnunciado,
                                            String novaDataInicio, String novaDataFim) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        if (perguntaTemRespostas(perguntaId)) {
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

    /**
     * Edita uma opção de uma pergunta (apenas se a pergunta não tiver respostas)
     */
    public synchronized void editarOpcao(int opcaoId, int perguntaId, String novoTexto, boolean novaCorreta) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        if (perguntaTemRespostas(perguntaId)) {
            throw new SQLException("Não é possível editar opção: pergunta já tem respostas");
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

    /**
     * Elimina uma pergunta (apenas se não tiver respostas)
     */
    public synchronized void eliminarPergunta(int perguntaId) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        if (perguntaTemRespostas(perguntaId)) {
            throw new SQLException("Não é possível eliminar: pergunta já tem respostas");
        }

        // Eliminar opções primeiro (CASCADE deveria fazer isto, mas vamos garantir)
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

    /**
     * Obtém detalhes completos de uma pergunta expirada com respostas
     */
    public synchronized PerguntaDetalhes obterDetalhesPerguntaExpirada(int perguntaId, int docenteId) throws SQLException {
        if (connection == null || connection.isClosed()) connect();

        // Verificar se pertence ao docente
        if (!perguntaPertenceADocente(perguntaId, docenteId)) {
            throw new SQLException("Pergunta não pertence ao docente");
        }

        PerguntaDetalhes pd = new PerguntaDetalhes();

        // Obter dados da pergunta
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

                // Verificar se está expirada
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

        // Obter opções
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

                    // Contar quantos escolheram esta opção
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

        // Obter respostas dos estudantes
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

    /**
     * Exporta resultados de uma pergunta para formato CSV
     */
    public synchronized String exportarParaCSV(int perguntaId, int docenteId) throws SQLException {
        PerguntaDetalhes pd = obterDetalhesPerguntaExpirada(perguntaId, docenteId);

        StringBuilder csv = new StringBuilder();

        // Cabeçalho - informação da pergunta
        csv.append("\"dia\";\"hora inicial\";\"hora final\";\"enunciado da pergunta\";\"opção certa\"\n");

        // Extrair dia e horas
        String dia = pd.dataInicio.substring(0, 10); // AAAA-MM-DD
        String horaInicial = pd.dataInicio.substring(11, 16); // HH:mm
        String horaFinal = pd.dataFim.substring(11, 16);

        // Encontrar letra da opção correta
        String letraCorreta = "";
        for (PerguntaDetalhes.OpcaoDetalhes op : pd.opcoes) {
            if (op.isCorreta) {
                letraCorreta = op.letra;
                break;
            }
        }

        csv.append(String.format("\"%s\";\"%s\";\"%s\";\"%s\";\"%s\"\n",
                dia, horaInicial, horaFinal,
                pd.enunciado.replace("\"", "\"\""), // escapar aspas
                letraCorreta));

        // Opções
        csv.append("\n\"opção\";\"texto da opção\"\n");
        for (PerguntaDetalhes.OpcaoDetalhes op : pd.opcoes) {
            csv.append(String.format("\"%s\";\"%s\"\n",
                    op.letra,
                    op.texto.replace("\"", "\"\"")));
        }

        // Respostas dos estudantes
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