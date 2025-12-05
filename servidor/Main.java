package servidor;

import servidor.db.DatabaseManager;
import java.net.*;
import java.sql.*;
import servidor.handlers.ClienteHandler;
import java.io.*;
import java.nio.file.*;

public class Main {
    private static final String MULTICAST_ADDRESS = "230.30.30.30";
    private static final int MULTICAST_PORT = 3030;

    private static volatile boolean ehPrincipal = false;
    private static volatile int portoTCPClientes = 0;
    private static volatile int portoTCPSync = 0;
    private static InetAddress meuIP;

    public static void main(String[] args) {
      
            String ipDiretoria = "127.0.0.1";
            int portoDiretoria = 4000;          
            String pastaBD = "servidor";        

            if (args.length >= 1) {
                ipDiretoria = args[0];
            }
            if (args.length >= 2) {
                try {
                    portoDiretoria = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    System.err.println("[Servidor] Porto da diretoria inválido, a usar 4000 por defeito.");
                }
            }
            if (args.length >= 3) {
                pastaBD = args[2];
            }

            final String ipDiretoriaFinal = ipDiretoria;
            final int portoDiretoriaFinal = portoDiretoria;
            Path pastaBDPath = Paths.get(pastaBD);
            try {
                Files.createDirectories(pastaBDPath);
            } catch (IOException e) {
                System.err.println("[Servidor] Não foi possível criar diretoria da BD: " + e.getMessage());
            }
            String dbPath = pastaBDPath.resolve("sistema.db").toString();

            System.out.println("[Servidor] Config:");
            System.out.println("  Diretoria IP     = " + ipDiretoria);
            System.out.println("  Diretoria Porto  = " + portoDiretoria);
            System.out.println("  Pasta BD         = " + pastaBD);
            System.out.println("  Ficheiro BD      = " + dbPath);

            DatabaseManager db;

        try {
            InetAddress ipDiretoria_addr = InetAddress.getByName(ipDiretoria);
            meuIP = InetAddress.getLocalHost();
            DatagramSocket socket = new DatagramSocket();

            ServerSocket servidorClientes = new ServerSocket(0);
            portoTCPClientes = servidorClientes.getLocalPort();

            ServerSocket servidorSync = new ServerSocket(0);
            portoTCPSync = servidorSync.getLocalPort();

            System.out.println("[Servidor] Portos TCP atribuídos -> Clientes: " + portoTCPClientes + " | Sync: " + portoTCPSync);

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

            socket.setSoTimeout(3000);

            try {
                socket.receive(respostaPacket);
            } catch (SocketTimeoutException e) {
                System.err.println("[Servidor] ERRO: Diretoria não respondeu no tempo limite (3s).");
                System.err.println("[Servidor] A terminar conforme o enunciado exige.");
                return;
            }

            socket.setSoTimeout(0);
            String resposta = new String(respostaPacket.getData(), 0, respostaPacket.getLength());
            System.out.println("[Servidor] Resposta da diretoria: " + resposta);

            String principalStr = resposta.trim();
            String[] hp = principalStr.split(":");
            String hostP = hp[0];
            int portP = Integer.parseInt(hp[1]);

            int portoPrincipalSync = 0;
            if (hp.length >= 3) {
                portoPrincipalSync = Integer.parseInt(hp[2]);
            }

            boolean ipEhLocal =
                    hostP.equals("127.0.0.1") ||
                            hostP.equalsIgnoreCase("localhost") ||
                            hostP.equals(meuIP.getHostAddress()) ||
                            isLocalAddress(hostP);

            boolean portoIgual = (portP == portoTCPClientes);

            ehPrincipal = portoIgual && ipEhLocal;

            System.out.printf("[Servidor] Identificação: principal=%s | principalDir=%s | meu=%s:%d%n",
                    ehPrincipal ? "SIM" : "NAO", principalStr, meuIP.getHostAddress(), portoTCPClientes);

            if (!ehPrincipal && portoPrincipalSync != 0) {
                sincronizarBaseDeDadosComPrincipal(hostP, portoPrincipalSync, dbPath);
            }

            db = new DatabaseManager(dbPath);
            db.connect();
            db.createTables();
            if (ehPrincipal) {
                iniciarServidorSync(servidorSync, dbPath, db);
            }

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

                     System.out.println("[Multicast] Recebido de " + remetenteIP.getHostAddress() + ": " + mensagemRecebida);

                     if (!ehPrincipal) {
                        synchronized (db) {
                            processarHeartbeatMulticast(mensagemRecebida, db);
                        }
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

         new Thread(() -> {
            try {
                byte[] bufAck = new byte[256];
                DatagramPacket ackPacket = new DatagramPacket(bufAck, bufAck.length);

                while (true) {
                    Thread.sleep(5000);

                    int versaoAtual = db.getVersao();

                    String hbMsg = "HEARTBEAT:" + versaoAtual + ":" + portoTCPClientes + ":" + portoTCPSync;
                    byte[] hbBytes = hbMsg.getBytes();

                    DatagramPacket hbPacket = new DatagramPacket(
                            hbBytes,
                            hbBytes.length,
                            ipDiretoria_addr,
                            portoDiretoriaFinal
                    );
                    socket.send(hbPacket);
                    System.out.println("[Servidor] HEARTBEAT enviado para diretoria (versão="
                            + versaoAtual + ", portoTCP=" + portoTCPClientes + ")");

                    try {
                        socket.setSoTimeout(1000);
                        socket.receive(ackPacket);
                        String ackStr = new String(ackPacket.getData(), 0, ackPacket.getLength());

                        if (ackStr.startsWith("ACK_HEARTBEAT:")) {
                            String papel = ackStr.substring("ACK_HEARTBEAT:".length()).trim();
                            boolean novoEhPrincipal = papel.equalsIgnoreCase("PRINCIPAL");
                            if (novoEhPrincipal != ehPrincipal) {
                                ehPrincipal = novoEhPrincipal;
                                System.out.println("[Servidor] Atualização de papel: agora sou "
                                        + (ehPrincipal ? "PRINCIPAL" : "SECUNDARIO"));
                            }
                        }
                    } catch (SocketTimeoutException ste) {
                    } catch (Exception ex) {
                        System.err.println("[Servidor] Erro ao receber ACK_HEARTBEAT: " + ex.getMessage());
                    } finally {
                        socket.setSoTimeout(0);
                    }

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
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Servidor] Erro no heartbeat (diretoria): " + e.getMessage());
            }
        }, "HB-Thread").start();

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

            System.out.println("[Servidor] Servidor totalmente operacional! (UDP + TCP + DB + MULTICAST)");
        } catch (Exception e) {
            System.err.println("[Servidor] Erro UDP/TCP inicial: " + e.getMessage());
            e.printStackTrace();
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
                System.out.println("[Multicast] Update recebido - Versão recebida: "
                        + versaoRecebida + " | Versão local: " + versaoLocal);
                if (versaoRecebida <= versaoLocal) {
                    System.out.println("[Multicast] Update antigo/duplicado, a ignorar.");
                    return;
                }
                if (versaoRecebida > versaoLocal + 1) {
                    System.err.println("[Multicast] PERDA DE SINCRONIZAÇÃO! Esperava versão "
                            + (versaoLocal + 1) + ", recebi " + versaoRecebida);
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
                    System.err.println("[Multicast] Versões diferentes! Local: " + versaoLocal
                            + ", Principal: " + versaoRecebida);
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

    private static void sincronizarBaseDeDadosComPrincipal(
            String hostPrincipal,
            int portoTcpsync,
            String caminhoDbLocal
    ) throws IOException {

        Path pathDb = Paths.get(caminhoDbLocal);
        Files.createDirectories(pathDb.getParent());

        try (Socket s = new Socket(hostPrincipal, portoTcpsync);
             InputStream in = s.getInputStream();
             OutputStream out = Files.newOutputStream(
                     pathDb,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {

            byte[] buffer = new byte[8192];
            int lido;
            while ((lido = in.read(buffer)) != -1) {
                out.write(buffer, 0, lido);
            }
        }

        System.out.println("[Sync] Download da BD do principal concluído.");
    }

    private static void iniciarServidorSync(ServerSocket servidorSync, String caminhoDb, DatabaseManager db) {
        Thread t = new Thread(() -> {
            try (ServerSocket ss = servidorSync) {
                System.out.println("[Sync] Servidor de sync a escutar em " + ss.getLocalPort());

                while (true) {
                    Socket cli = ss.accept();
                    System.out.println("[Sync] Pedido de sync de " + cli.getInetAddress());

                    synchronized (db) {
                        try (OutputStream out = cli.getOutputStream();
                            InputStream in = new FileInputStream(caminhoDb)) {

                            byte[] buffer = new byte[8192];
                            int lido;
                            while ((lido = in.read(buffer)) != -1) {
                                out.write(buffer, 0, lido);
                            }
                            out.flush();
                        } catch (IOException e) {
                            System.err.println("[Sync] Erro a enviar BD: " + e.getMessage());
                        } finally {
                            try { cli.close(); } catch (IOException ignore) {}
                        }
                    }
                }
            } catch (IOException e) {
                System.err.println("[Sync] Erro no servidor de sync: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }
}