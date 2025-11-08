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

    
    public int getVersao() {
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT versao FROM Configuracao WHERE id = 1");
            if (rs.next()) {
                int versao = rs.getInt("versao");
                rs.close();
                stmt.close();
                return versao;
            }
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao obter versão: " + e.getMessage());
        }
        return 0;
    }

 
    public void incrementarVersao() {
        try {
            Statement stmt = connection.createStatement();
            stmt.executeUpdate("UPDATE Configuracao SET versao = versao + 1 WHERE id = 1");
            stmt.close();
            System.out.println("[DB] Versão incrementada para: " + getVersao());
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao incrementar versão: " + e.getMessage());
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
    public boolean autenticarDocente(String email, String password) throws SQLException {
        String sql = "SELECT password_hash FROM Docente WHERE email = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, email);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            String hash = rs.getString("password_hash");
            return hash.equals(hashPassword(password));
        }
        return false;
    }

    public boolean autenticarEstudante(String email, String password) throws SQLException {
        String sql = "SELECT password_hash FROM Estudante WHERE email = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, email);
        ResultSet rs = ps.executeQuery();
        if (rs.next()) {
            String hash = rs.getString("password_hash");
            return hash.equals(hashPassword(password));
        }
        return false;
    }
    public int criarPergunta(int docenteId, String enunciado, String dataInicio, String dataFim) throws SQLException {
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
        return id;
    }

    public void adicionarOpcao(int perguntaId, String letra, String texto, boolean correta) throws SQLException {
        String sql = "INSERT INTO Opcao (pergunta_id, letra, texto, is_correta) VALUES (?, ?, ?, ?)";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setInt(1, perguntaId);
        ps.setString(2, letra);
        ps.setString(3, texto);
        ps.setInt(4, correta ? 1 : 0);
        ps.executeUpdate();
        ps.close();
    }
    public void guardarResposta(int estudanteId, int perguntaId, String letra) throws SQLException {
        String sql = "INSERT OR REPLACE INTO Resposta (estudante_id, pergunta_id, opcao_letra) VALUES (?, ?, ?)";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setInt(1, estudanteId);
        ps.setInt(2, perguntaId);
        ps.setString(3, letra);
        ps.executeUpdate();
        ps.close();
    }
    public int getDocenteId(String email) throws SQLException {
        String sql = "SELECT id FROM Docente WHERE email = ?";
        PreparedStatement ps = connection.prepareStatement(sql);
        ps.setString(1, email);
        ResultSet rs = ps.executeQuery();
        int id = rs.next() ? rs.getInt("id") : -1;
        ps.close();
        return id;
    }

}