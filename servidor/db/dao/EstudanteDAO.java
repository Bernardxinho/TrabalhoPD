package servidor.db.dao;

import servidor.db.util.SecurityUtil;
import java.sql.*;

public class EstudanteDAO {
    private final Connection connection;

    public EstudanteDAO(Connection connection) {
        this.connection = connection;
    }

    public boolean autenticar(String email, String password) throws SQLException {
        String sql = "SELECT password_hash FROM Estudante WHERE email = ?";
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
        String sql = "SELECT id FROM Estudante WHERE email = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("id") : -1;
            }
        }
    }

    public int criar(int numero, String nome, String email, String passwordClaro) throws SQLException {
        String sql = "INSERT INTO Estudante (numero, nome, email, password_hash) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, numero);
            ps.setString(2, nome);
            ps.setString(3, email);
            ps.setString(4, SecurityUtil.hashPassword(passwordClaro));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    public void atualizarPerfil(int estudanteId, String novoNome, String novoEmail, String novaPass) throws SQLException {
        String sql = "UPDATE Estudante SET nome=?, email=?, password_hash=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, novoNome);
            ps.setString(2, novoEmail);
            ps.setString(3, SecurityUtil.hashPassword(novaPass));
            ps.setInt(4, estudanteId);
            ps.executeUpdate();
        }
    }
}