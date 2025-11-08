package servidor;

import servidor.db.DatabaseManager;
import java.net.*;
import java.sql.*;

public class Main {
    public static void main(String[] args) {
        String dbPath = "servidor/sistema.db";
        DatabaseManager db = new DatabaseManager(dbPath);
        db.connect();
        db.createTables();

        try {
            String ipDiretoria = "127.0.0.1";
            int portoDiretoria = 4000;
            InetAddress ip = InetAddress.getByName(ipDiretoria);
            DatagramSocket socket = new DatagramSocket();

            ServerSocket servidorClientes = new ServerSocket(0);
            int portoTCP = servidorClientes.getLocalPort();

            ServerSocket servidorSync = new ServerSocket(0);
            int portoSync = servidorSync.getLocalPort();

            System.out.println("[Servidor] Portos TCP atribuídos -> Clientes: " + portoTCP + " | Sync: " + portoSync);

            String mensagem = "REGISTO:" + portoTCP + ":" + portoSync;
            byte[] dados = mensagem.getBytes();
            DatagramPacket packet = new DatagramPacket(dados, dados.length, ip, portoDiretoria);
            socket.send(packet);
            System.out.println("[Servidor] Pedido de registo enviado para diretoria.");

            byte[] buffer = new byte[1024];
            DatagramPacket respostaPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(respostaPacket);
            String resposta = new String(respostaPacket.getData(), 0, respostaPacket.getLength());
            System.out.println("[Servidor] Resposta da diretoria: " + resposta);

            // ===== HEARTBEAT THREAD =====
            new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(5000);
                        String hbMsg = "HEARTBEAT:" + portoTCP + ":" + portoSync;
                        byte[] hbBytes = hbMsg.getBytes();
                        DatagramPacket hbPacket = new DatagramPacket(hbBytes, hbBytes.length, ip, portoDiretoria);
                        socket.send(hbPacket);
                        System.out.println("[Servidor] Heartbeat enviado.");
                    }
                } catch (Exception e) {
                    System.err.println("[Servidor] Erro no heartbeat: " + e.getMessage());
                }
            }, "HB-Thread").start();

            // ===== TCP CLIENT HANDLER =====
            new Thread(() -> {
                try {
                    System.out.println("[Servidor] À escuta de clientes em TCP no porto " + portoTCP);

                    while (true) {
                        Socket cliente = servidorClientes.accept();
                        System.out.println("[Servidor] Cliente conectado: " + cliente.getInetAddress().getHostAddress());

                        try (
    java.io.BufferedReader in = new java.io.BufferedReader(
        new java.io.InputStreamReader(cliente.getInputStream()));
    java.io.PrintWriter out = new java.io.PrintWriter(cliente.getOutputStream(), true)
) {
    String msg;
    // lê até o cliente fechar o socket
    while ((msg = in.readLine()) != null) {
        System.out.println("[Servidor] Recebido do cliente: " + msg);

        if (msg.startsWith("LOGIN_DOCENTE")) {
            String[] partes = msg.split(";");
            String email = partes[1];
            String password = partes[2];
            boolean ok = db.autenticarDocente(email, password);
            out.println(ok ? "LOGIN_OK" : "LOGIN_FAIL");
        }

        else if (msg.startsWith("CRIAR_PERGUNTA")) {
            String[] partes = msg.split(";");
            int docenteId = Integer.parseInt(partes[1]);
            String enunciado = partes[2];
            String inicio = partes[3];
            String fim = partes[4];
            int idPergunta = db.criarPergunta(docenteId, enunciado, inicio, fim);
            out.println("PERGUNTA_CRIADA:" + idPergunta);
        }

        else if (msg.startsWith("ADICIONAR_OPCAO")) {
            String[] partes = msg.split(";");
            int perguntaId = Integer.parseInt(partes[1]);
            String letra = partes[2];
            String texto = partes[3];
            boolean correta = partes[4].equals("1");
            db.adicionarOpcao(perguntaId, letra, texto, correta);
            out.println("OPCAO_ADICIONADA");
        }

        else if (msg.startsWith("RESPONDER")) {
            String[] partes = msg.split(";");
            int estudanteId = Integer.parseInt(partes[1]);
            int perguntaId = Integer.parseInt(partes[2]);
            String letra = partes[3];
            db.guardarResposta(estudanteId, perguntaId, letra);
            out.println("RESPOSTA_GUARDADA");
        }

        else {
            out.println("COMANDO_DESCONHECIDO");
        }
    }
    System.out.println("[Servidor] Cliente desligou.");
}
}
}catch (Exception e) {
                    System.err.println("[Servidor] Erro TCP: " + e.getMessage());
                }
            }, "TCP-Clientes").start();

        } catch (Exception e) {
            System.err.println("[Servidor] Erro UDP/TCP inicial: " + e.getMessage());
        }

        // ===== SEED DE DADOS DE TESTE =====
        try (Connection conn = db.getConnection()) {
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
                System.out.println("[DB] Docente exemplo criado (email: docente@isec.pt | pass: 1234)");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Erro ao inserir docente de teste: " + e.getMessage());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            db.close();
            System.out.println("[Servidor] Encerrado com segurança.");
        }));

        System.out.println("[Servidor] Servidor totalmente operacional! (UDP + TCP + DB)");
    }
}
