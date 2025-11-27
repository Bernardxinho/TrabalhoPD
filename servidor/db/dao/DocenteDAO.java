package servidor.db.dao;

import servidor.db.util.SecurityUtil;
import java.sql.*;

public class DocenteDAO {
    private final Connection connection;

    public DocenteDAO(Connection connection) {
        this.connection = connection;
    }

    public boolean autenticar(String email, String password) throws SQLException {
        String sql = "SELECT password_hash FROM Docente WHERE email = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String hash = rs.getString("password_hash");
                    return hash.equals(SecurityUtil.hashPassword(password));
                }
            }
        }
        return false;
    }

    public int getId(String email) throws SQLException {
        String sql = "SELECT id FROM Docente WHERE email = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : -1;
            }
        }
    }

    public boolean validarCodigoRegistro(String codigoClaro) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT codigo_registo_docentes FROM Configuracao WHERE id=1")) {
            if (!rs.next()) return false;
            String hashBD = rs.getString(1);
            String hashIntroduzido = SecurityUtil.hashPassword(codigoClaro);
            return hashBD != null && hashBD.equals(hashIntroduzido);
        }
    }

    public int criar(String nome, String email, String passwordClaro) throws SQLException {
        String sql = "INSERT INTO Docente (nome, email, password_hash) VALUES (?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, nome);
            ps.setString(2, email);
            ps.setString(3, SecurityUtil.hashPassword(passwordClaro));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    public void atualizarPerfil(int docenteId, String novoNome, String novoEmail, String novaPasswordClaro) throws SQLException {
        String sql = "UPDATE Docente SET nome = ?, email = ?, password_hash = ? WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, novoNome);
            ps.setString(2, novoEmail);
            ps.setString(3, SecurityUtil.hashPassword(novaPasswordClaro));
            ps.setInt(4, docenteId);
            ps.executeUpdate();
        }
    }
}