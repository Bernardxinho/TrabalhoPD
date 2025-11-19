package servidor;

import servidor.db.DatabaseManager;
import servidor.db.PerguntaDetalhes;
import java.net.*;
import java.sql.*;
import java.io.IOException;
import servidor.handlers.ClienteHandler;
import servidor.ReplicationSender;


public class Main {
    private static final String MULTICAST_ADDRESS = "230.30.30.30";
    private static final int MULTICAST_PORT = 3030;

    // Variáveis partilhadas entre threads
    private static volatile boolean ehPrincipal = false;
    private static volatile int portoTCPClientes = 0;
    private static volatile int portoTCPSync = 0;
    private static InetAddress meuIP;


    private static class Sessao {
        boolean autenticado = false;
        String role = null;          // "DOCENTE" ou "ESTUDANTE"
        Integer docenteId = null;
        Integer estudanteId = null;
    }

    public static void main(String[] args) {
        String dbPath = "servidor/sistema.db";
        DatabaseManager db = new DatabaseManager(dbPath);
        db.connect();
        db.createTables();

        try {
            String ipDiretoria = "127.0.0.1";
            int portoDiretoria = 4000;
            InetAddress ipDiretoria_addr = InetAddress.getByName(ipDiretoria);
            meuIP = InetAddress.getLocalHost();
            DatagramSocket socket = new DatagramSocket();

            ServerSocket servidorClientes = new ServerSocket(0);
            portoTCPClientes = servidorClientes.getLocalPort();

            ServerSocket servidorSync = new ServerSocket(0);
            portoTCPSync = servidorSync.getLocalPort();

            System.out.println("[Servidor] Portos TCP atribuídos -> Clientes: " + portoTCPClientes + " | Sync: " + portoTCPSync);

            // ===== PREPARAR MULTICAST =====
            InetAddress grupoMulticast = InetAddress.getByName(MULTICAST_ADDRESS);

                ReplicationSender replicator = new ReplicationSender(
                        socket,
                        grupoMulticast,
                        MULTICAST_PORT,
                        portoTCPClientes,
                        portoTCPSync
                );

            String mensagem = "REGISTO:" + portoTCPClientes + ":" + portoTCPSync;
            byte[] dados = mensagem.getBytes();
            DatagramPacket packet = new DatagramPacket(dados, dados.length, ipDiretoria_addr, portoDiretoria);
            socket.send(packet);
            System.out.println("[Servidor] Pedido de registo enviado para diretoria.");

            byte[] buffer = new byte[1024];
            DatagramPacket respostaPacket = new DatagramPacket(buffer, buffer.length);
            socket.receive(respostaPacket);
            String resposta = new String(respostaPacket.getData(), 0, respostaPacket.getLength());
            System.out.println("[Servidor] Resposta da diretoria: " + resposta);

            // Determinar se sou o principal
            byte[] pedidoPrincipal = "PEDIDO_CLIENTE_SERVIDOR".getBytes();
            DatagramPacket pedPacket = new DatagramPacket(
                    pedidoPrincipal, pedidoPrincipal.length,
                    ipDiretoria_addr, portoDiretoria);
            socket.send(pedPacket);

            byte[] bufResp = new byte[1024];
            DatagramPacket respPrincipal = new DatagramPacket(bufResp, bufResp.length);
            socket.receive(respPrincipal);
            String principal = new String(respPrincipal.getData(), 0, respPrincipal.getLength());

            String principalStr = principal.trim();
            String[] hp = principalStr.split(":");
            String hostP = hp[0];
            int portP = Integer.parseInt(hp[1]);

            boolean ipEhLocal =
                    hostP.equals("127.0.0.1") ||
                            hostP.equalsIgnoreCase("localhost") ||
                            hostP.equals(meuIP.getHostAddress()) ||
                            isLocalAddress(hostP);

            boolean portoIgual = (portP == portoTCPClientes);

            ehPrincipal = portoIgual && ipEhLocal;

            System.out.printf("[Servidor] Identificação: principal=%s | principalDir=%s | meu=%s:%d%n",
                    ehPrincipal ? "SIM" : "NAO", principalStr, meuIP.getHostAddress(), portoTCPClientes);


            // ===== THREAD DE RECEÇÃO DE HEARTBEATS MULTICAST =====
            new Thread(() -> {
                MulticastSocket multicastSocket = null;
                try {
                    multicastSocket = new MulticastSocket(MULTICAST_PORT);
                    InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);

                    SocketAddress grupoAddr = new InetSocketAddress(grupo, MULTICAST_PORT);
                    NetworkInterface netInterface = NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
                    multicastSocket.joinGroup(grupoAddr, netInterface);

                    System.out.println("[Servidor] À escuta de heartbeats multicast em " + MULTICAST_ADDRESS + ":" + MULTICAST_PORT);

                    byte[] bufferMcast = new byte[4096];

                    while (true) {
                        DatagramPacket packetMcast = new DatagramPacket(bufferMcast, bufferMcast.length);
                        multicastSocket.receive(packetMcast);

                        String mensagemRecebida = new String(packetMcast.getData(), 0, packetMcast.getLength());
                        InetAddress remetenteIP = packetMcast.getAddress();

                        if (remetenteIP.equals(meuIP)) {
                            continue;
                        }

                        System.out.println("[Multicast] Recebido: " + mensagemRecebida);

                        if (!ehPrincipal) {
                            processarHeartbeatMulticast(mensagemRecebida, db);
                        }
                    }

                } catch (Exception e) {
                    System.err.println("[Servidor] Erro ao receber multicast: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    if (multicastSocket != null) {
                        multicastSocket.close();
                    }
                }
            }, "Multicast-Receiver").start();

            // ===== HEARTBEAT THREAD (UDP + MULTICAST) =====
            // ===== HEARTBEAT THREAD (UDP + MULTICAST) =====
            new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(5000);

                        int versaoAtual = db.getVersao();

                        String hbMsg = "HEARTBEAT:" + versaoAtual + ":" + portoTCPClientes + ":" + portoTCPSync;
                        byte[] hbBytes = hbMsg.getBytes();

                        // Heartbeat para a diretoria (ESSENCIAL)
                        DatagramPacket hbPacket = new DatagramPacket(
                                hbBytes,
                                hbBytes.length,
                                ipDiretoria_addr,
                                portoDiretoria
                        );
                        socket.send(hbPacket);

                        // Heartbeat multicast (opcional, não pode matar a thread)
                        if (ehPrincipal) {
                            try {
                                DatagramPacket multicastPacket = new DatagramPacket(
                                        hbBytes,
                                        hbBytes.length,
                                        grupoMulticast,
                                        MULTICAST_PORT
                                );
                                socket.send(multicastPacket);
                            } catch (Exception me) {
                                System.err.println("[Servidor] Erro ao enviar heartbeat multicast: " + me.getMessage());
                                // ignora, segue a vida — diretoria continua a receber
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Servidor] Erro no heartbeat (diretoria): " + e.getMessage());
                }
            }, "HB-Thread").start();
            // ===== TCP CLIENT HANDLER =====
            new Thread(() -> {
                try {
                    System.out.println("[Servidor] À escuta de clientes em TCP no porto " + portoTCPClientes);
                    while (true) {
                        Socket cliente = servidorClientes.accept();

                        if (!ehPrincipal) {
                            System.out.println("[Servidor] Não sou principal, rejeitando cliente.");
                            cliente.close();
                            continue;
                        }

                        new Thread(new ClienteHandler(cliente, db, replicator), "Cliente-Handler").start();
                    }
                } catch (Exception e) {
                    System.err.println("[Servidor] Erro TCP: " + e.getMessage());
                }
            }, "TCP-Clientes").start();

        } catch (Exception e) {
            System.err.println("[Servidor] Erro UDP/TCP inicial: " + e.getMessage());
            e.printStackTrace();
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

        System.out.println("[Servidor] Servidor totalmente operacional! (UDP + TCP + DB + MULTICAST)");
    }

    private static void enviarHeartbeatComQuery(DatagramSocket socket, InetAddress grupoMulticast, int versao, String querySql) {
        try {
            String hbMsg = "HEARTBEAT_UPDATE:" + versao + ":" + portoTCPClientes + ":" + portoTCPSync + ":QUERY:" + querySql;
            byte[] hbBytes = hbMsg.getBytes();

            DatagramPacket multicastPacket = new DatagramPacket(
                    hbBytes,
                    hbBytes.length,
                    grupoMulticast,
                    MULTICAST_PORT
            );
            socket.send(multicastPacket);
            System.out.println("[Servidor] Heartbeat UPDATE enviado via multicast (versão: " + versao + ")");
            System.out.println("           Query: " + querySql);
        } catch (Exception e) {
            System.err.println("[Servidor] Erro ao enviar heartbeat com query: " + e.getMessage());
        }
    }

    private static void processarHeartbeatMulticast(String mensagem, DatabaseManager db) {
        try {
            if (mensagem.startsWith("HEARTBEAT_UPDATE:")) {
                String[] partes = mensagem.split(":", 6);

                if (partes.length < 6) {
                    System.err.println("[Multicast] Formato inválido: " + mensagem);
                    return;
                }

                int versaoRecebida = Integer.parseInt(partes[1]);
                String query = partes[5];

                int versaoLocal = db.getVersao();

                System.out.println("[Multicast] Update recebido - Versão recebida: " + versaoRecebida + " | Versão local: " + versaoLocal);

                if (versaoRecebida != versaoLocal + 1) {
                    System.err.println("[Multicast] PERDA DE SINCRONIZAÇÃO! Esperava versão " + (versaoLocal + 1) + ", recebi " + versaoRecebida);
                    System.err.println("[Multicast] Servidor vai terminar!");
                    System.exit(1);
                }

                try {
                    db.executarQuery(query);
                    db.incrementarVersao();

                    System.out.println("[Multicast] Query executada com sucesso! Nova versão: " + db.getVersao());
                    System.out.println("           Query: " + query);

                } catch (SQLException e) {
                    System.err.println("[Multicast] Erro ao executar query: " + e.getMessage());
                    System.err.println("[Multicast] Servidor vai terminar!");
                    System.exit(1);
                }

            } else if (mensagem.startsWith("HEARTBEAT:")) {
                String[] partes = mensagem.split(":");

                if (partes.length < 4) {
                    return;
                }

                int versaoRecebida = Integer.parseInt(partes[1]);
                int versaoLocal = db.getVersao();

                if (versaoRecebida != versaoLocal) {
                    System.err.println("[Multicast] Versões diferentes! Local: " + versaoLocal + ", Principal: " + versaoRecebida);
                    System.err.println("[Multicast] PERDA DE SINCRONIZAÇÃO! Servidor vai terminar!");
                    System.exit(1);
                }
            }

        } catch (Exception e) {
            System.err.println("[Multicast] Erro ao processar heartbeat: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean isLocalAddress(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress()) return true;
            java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                java.net.NetworkInterface ni = nics.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    if (addrs.nextElement().equals(addr)) return true;
                }
            }
        } catch (Exception ignore) {}
        return false;
    }

    private static int getEstudanteId(DatabaseManager db, String email) throws SQLException {
        try (PreparedStatement ps = db.getConnection()
                .prepareStatement("SELECT id FROM Estudante WHERE email=?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}