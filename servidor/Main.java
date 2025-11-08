package servidor;

import servidor.db.DatabaseManager;
import java.net.*;
import java.sql.*;
import java.io.IOException;

public class Main {
    private static final String MULTICAST_ADDRESS = "230.30.30.30";
    private static final int MULTICAST_PORT = 3030;

    // Variáveis partilhadas entre threads
    private static volatile boolean ehPrincipal = false;
    private static volatile int portoTCPClientes = 0;
    private static volatile int portoTCPSync = 0;
    private static InetAddress meuIP;

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
            // A diretoria retorna info do servidor principal (o mais antigo registado)
            // Se for o meu porto, sou o principal
            if (resposta.contains(":" + portoTCPSync)) {
                ehPrincipal = true;
                System.out.println("[Servidor] SOU O SERVIDOR PRINCIPAL!");
            } else {
                ehPrincipal = false;
                System.out.println("[Servidor] Sou servidor BACKUP");
            }

            // ===== THREAD DE RECEÇÃO DE HEARTBEATS MULTICAST =====
            new Thread(() -> {
                MulticastSocket multicastSocket = null;
                try {
                    multicastSocket = new MulticastSocket(MULTICAST_PORT);
                    InetAddress grupo = InetAddress.getByName(MULTICAST_ADDRESS);

                    // Join ao grupo multicast
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

                        // Ignorar mensagens do próprio servidor
                        if (remetenteIP.equals(meuIP)) {
                            continue;
                        }

                        System.out.println("[Multicast] Recebido: " + mensagemRecebida);

                        // Processar apenas se NÃO sou o principal
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
            new Thread(() -> {
                try {
                    while (true) {
                        Thread.sleep(5000);

                        int versaoAtual = db.getVersao();

                        // Formato: HEARTBEAT:versao:portoClientes:portoSync
                        String hbMsg = "HEARTBEAT:" + versaoAtual + ":" + portoTCPClientes + ":" + portoTCPSync;
                        byte[] hbBytes = hbMsg.getBytes();

                        // 1. Enviar para a diretoria (UDP)
                        DatagramPacket hbPacket = new DatagramPacket(hbBytes, hbBytes.length, ipDiretoria_addr, portoDiretoria);
                        socket.send(hbPacket);
                        System.out.println("[Servidor] Heartbeat enviado para diretoria.");

                        // 2. Enviar para o grupo multicast (APENAS se sou o principal)
                        if (ehPrincipal) {
                            DatagramPacket multicastPacket = new DatagramPacket(
                                    hbBytes,
                                    hbBytes.length,
                                    grupoMulticast,
                                    MULTICAST_PORT
                            );
                            socket.send(multicastPacket);
                            System.out.println("[Servidor] Heartbeat multicast enviado (versão BD: " + versaoAtual + ")");
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Servidor] Erro no heartbeat: " + e.getMessage());
                }
            }, "HB-Thread").start();

            // ===== TCP CLIENT HANDLER =====
            new Thread(() -> {
                try {
                    System.out.println("[Servidor] À escuta de clientes em TCP no porto " + portoTCPClientes);

                    while (true) {
                        Socket cliente = servidorClientes.accept();

                        // Apenas o servidor principal atende clientes
                        if (!ehPrincipal) {
                            System.out.println("[Servidor] Não sou principal, rejeitando cliente.");
                            cliente.close();
                            continue;
                        }

                        System.out.println("[Servidor] Cliente conectado: " + cliente.getInetAddress().getHostAddress());

                        // Criar thread para cada cliente
                        new Thread(() -> {
                            try (
                                    java.io.BufferedReader in = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(cliente.getInputStream()));
                                    java.io.PrintWriter out = new java.io.PrintWriter(cliente.getOutputStream(), true)
                            ) {
                                String msg;
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

                                        DatabaseManager.PerguntaResult resultado = db.criarPerguntaCompleta(docenteId, enunciado, inicio, fim);
                                        db.incrementarVersao();

                                        out.println("PERGUNTA_CRIADA:" + resultado.id + ":" + resultado.codigoAcesso);

                                        // Construir a query SQL para replicação
                                        String querySql = String.format(
                                                "INSERT INTO Pergunta (enunciado, data_inicio, data_fim, codigo_acesso, docente_id) VALUES ('%s', '%s', '%s', '%s', %d)",
                                                enunciado.replace("'", "''"), inicio, fim, resultado.codigoAcesso, docenteId
                                        );

                                        // Enviar heartbeat imediato com a query
                                        enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);
                                    }

                                    else if (msg.startsWith("ADICIONAR_OPCAO")) {
                                        String[] partes = msg.split(";");
                                        int perguntaId = Integer.parseInt(partes[1]);
                                        String letra = partes[2];
                                        String texto = partes[3];
                                        boolean correta = partes[4].equals("1");

                                        // Construir a query SQL para replicação
                                        String querySql = String.format(
                                                "INSERT INTO Opcao (pergunta_id, letra, texto, is_correta) VALUES (%d, '%s', '%s', %d)",
                                                perguntaId, letra, texto.replace("'", "''"), correta ? 1 : 0
                                        );

                                        db.adicionarOpcao(perguntaId, letra, texto, correta);
                                        db.incrementarVersao();

                                        out.println("OPCAO_ADICIONADA");

                                        // Enviar heartbeat imediato com a query
                                        enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);
                                    }

                                    else if (msg.startsWith("RESPONDER")) {
                                        String[] partes = msg.split(";");
                                        int estudanteId = Integer.parseInt(partes[1]);
                                        int perguntaId = Integer.parseInt(partes[2]);
                                        String letra = partes[3];

                                        // Construir a query SQL para replicação
                                        String querySql = String.format(
                                                "INSERT OR REPLACE INTO Resposta (estudante_id, pergunta_id, opcao_letra) VALUES (%d, %d, '%s')",
                                                estudanteId, perguntaId, letra
                                        );

                                        db.guardarResposta(estudanteId, perguntaId, letra);
                                        db.incrementarVersao();

                                        out.println("RESPOSTA_GUARDADA");

                                        // Enviar heartbeat imediato com a query
                                        enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);
                                    }

                                    else {
                                        out.println("COMANDO_DESCONHECIDO");
                                    }
                                }
                                System.out.println("[Servidor] Cliente desligou.");
                            } catch (Exception e) {
                                System.err.println("[Servidor] Erro ao processar cliente: " + e.getMessage());
                            } finally {
                                try {
                                    cliente.close();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }
                        }, "Cliente-Handler").start();
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

    // ===== FUNÇÃO AUXILIAR: Enviar heartbeat com query SQL =====
    private static void enviarHeartbeatComQuery(DatagramSocket socket, InetAddress grupoMulticast, int versao, String querySql) {
        try {
            // Formato: HEARTBEAT_UPDATE:versao:portoClientes:portoSync:QUERY:querySQL
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

    // ===== FUNÇÃO: Processar heartbeat recebido via multicast =====
    private static void processarHeartbeatMulticast(String mensagem, DatabaseManager db) {
        try {
            if (mensagem.startsWith("HEARTBEAT_UPDATE:")) {
                // Formato: HEARTBEAT_UPDATE:versao:portoClientes:portoSync:QUERY:querySQL
                String[] partes = mensagem.split(":", 6);

                if (partes.length < 6) {
                    System.err.println("[Multicast] Formato inválido: " + mensagem);
                    return;
                }

                int versaoRecebida = Integer.parseInt(partes[1]);
                String query = partes[5];

                int versaoLocal = db.getVersao();

                System.out.println("[Multicast] Update recebido - Versão recebida: " + versaoRecebida + " | Versão local: " + versaoLocal);

                // Verificar se a versão está correta (local + 1)
                if (versaoRecebida != versaoLocal + 1) {
                    System.err.println("[Multicast] PERDA DE SINCRONIZAÇÃO! Esperava versão " + (versaoLocal + 1) + ", recebi " + versaoRecebida);
                    System.err.println("[Multicast] Servidor vai terminar!");
                    System.exit(1);
                }

                // Executar a query na base de dados local
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
                // Heartbeat normal (sem query) - apenas verificar versão
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
}