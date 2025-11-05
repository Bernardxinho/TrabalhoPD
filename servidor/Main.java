package servidor;

import servidor.db.DatabaseManager;
import java.net.*;
import java.sql.*;

public class Main {
    public static void main(String[] args) {
        String dbPath = "servidor/sistema.db";

        try {
            String ipDiretoria = "127.0.0.1";
            int portoDiretoria = 4000;
            DatagramSocket socket = new DatagramSocket();

            int portoTCP = 5050;
            String mensagem = "REGISTO:" + portoTCP;
            byte[] dados = mensagem.getBytes();
            InetAddress ip = InetAddress.getByName(ipDiretoria);
            DatagramPacket packet = new DatagramPacket(dados, dados.length, ip, portoDiretoria);
            socket.send(packet);
            System.out.println("[Servidor] Pedido de registo enviado para diretoria.");

            byte[] buffer = new byte[1024];
            DatagramPacket respostaPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(respostaPacket);
            String resposta = new String(respostaPacket.getData(), 0, respostaPacket.getLength());
            System.out.println("[Servidor] Resposta da diretoria: " + resposta);

            new Thread(() -> {
                try (DatagramSocket hbSocket = new DatagramSocket()) {
                    while (true) {
                        Thread.sleep(5000);
                        String hbMsg = "HEARTBEAT:" + portoTCP;
                        byte[] hbBytes = hbMsg.getBytes();
                        DatagramPacket hbPacket = new DatagramPacket(hbBytes, hbBytes.length, ip, portoDiretoria);
                        hbSocket.send(hbPacket);
                        System.out.println("[Servidor] Heartbeat enviado.");
                    }
                } catch (Exception e) {
                    System.err.println("[Servidor] Erro no heartbeat: " + e.getMessage());
                }
            }).start();

        } catch (Exception e) {
            System.err.println("[Servidor] Erro UDP: " + e.getMessage());
        }

        DatabaseManager db = new DatabaseManager(dbPath);
        db.connect();
        db.createTables();

        try {
            Connection conn = db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM Docente WHERE email = ?");
            ps.setString(1, "docente@isec.pt");
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                String hash = DatabaseManager.hashPassword("1234");
                PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO Docente(nome, email, password_hash) VALUES (?, ?, ?)");
                insert.setString(1, "Docente Exemplo");
                insert.setString(2, "docente@isec.pt");
                insert.setString(3, hash);
                insert.executeUpdate();
                insert.close();
                System.out.println("[DB] Docente exemplo criado (email: docente@isec.pt | pass: 1234)");
            } else {
                System.out.println("[DB] Docente de teste já existe.");
            }
            rs.close();
            ps.close();
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao inserir docente de teste: " + e.getMessage());
        }

        new Thread(() -> {
            try {
                int portoTCP = 5050;
                ServerSocket serverSocket = new ServerSocket(portoTCP);
                System.out.println("[Servidor] À escuta de clientes em TCP no porto " + portoTCP);

                while (true) {
                    Socket cliente = serverSocket.accept();
                    System.out.println("[Servidor] Cliente conectado: " + cliente.getInetAddress().getHostAddress());

                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new java.io.InputStreamReader(cliente.getInputStream()));
                    java.io.PrintWriter out = new java.io.PrintWriter(cliente.getOutputStream(), true);

                    String msg = in.readLine();
                    System.out.println("[Servidor] Recebido do cliente: " + msg);

                    out.println("Olá cliente! Servidor recebeu: " + msg);
                    cliente.close();
                }
            } catch (Exception e) {
                System.err.println("[Servidor] Erro TCP: " + e.getMessage());
            }
        }).start();

        try {
            Connection conn = db.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT id, nome, email, data_criacao FROM Docente");

            System.out.println("\n=== LISTA DE DOCENTES ===");
            while (rs.next()) {
                System.out.println("ID: " + rs.getInt("id") +
                        " | Nome: " + rs.getString("nome") +
                        " | Email: " + rs.getString("email") +
                        " | Criado em: " + rs.getString("data_criacao"));
            }
            System.out.println("=========================\n");

            rs.close();
            stmt.close();
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao listar docentes: " + e.getMessage());
        }

        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            db.close();
            System.out.println("[Servidor] Encerrado com segurança.");
        }));

        System.out.println("[Servidor] Servidor totalmente operacional! (UDP + TCP + DB)");
    }
}
