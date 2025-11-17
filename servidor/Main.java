package servidor;

import servidor.db.DatabaseManager;
import servidor.db.PerguntaDetalhes;
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

                        System.out.println("[Servidor] Cliente conectado: " + cliente.getInetAddress().getHostAddress());

                        new Thread(() -> {
                            Sessao sessao = new Sessao();
                            try (
                                    java.io.BufferedReader in = new java.io.BufferedReader(
                                            new java.io.InputStreamReader(cliente.getInputStream()));
                                    java.io.PrintWriter out = new java.io.PrintWriter(cliente.getOutputStream(), true)
                            ) {
                                String msg;
                                while ((msg = in.readLine()) != null) {
                                    System.out.println("[Servidor] Recebido do cliente: " + msg);

                                    if (msg.startsWith("LOGIN_DOCENTE")) {
                                        String[] p = msg.split(";");
                                        String email = p[1], pass = p[2];
                                        boolean ok = db.autenticarDocente(email, pass);
                                        if (ok) {
                                            sessao.autenticado = true;
                                            sessao.role = "DOCENTE";
                                            sessao.docenteId = db.getDocenteId(email);
                                            out.println("LOGIN_OK");
                                        } else out.println("LOGIN_FAIL");
                                    }
                                    else if (msg.startsWith("LOGIN_ESTUDANTE")) {
                                        String[] p = msg.split(";");
                                        String email = p[1], pass = p[2];
                                        boolean ok = db.autenticarEstudante(email, pass);
                                        if (ok) {
                                            sessao.autenticado = true;
                                            sessao.role = "ESTUDANTE";
                                            sessao.estudanteId = getEstudanteId(db, email);
                                            out.println("LOGIN_OK");
                                        } else out.println("LOGIN_FAIL");
                                    }
                                    else if (msg.startsWith("CRIAR_PERGUNTA")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }
                                        String[] p = msg.split(";");
                                        String enunciado, inicio, fim;
                                        if (p.length == 5) { enunciado = p[2]; inicio = p[3]; fim = p[4]; }
                                        else { enunciado = p[1]; inicio = p[2]; fim = p[3]; }

                                        var res = db.criarPerguntaCompleta(sessao.docenteId, enunciado, inicio, fim);
                                        db.incrementarVersao();
                                        out.println("PERGUNTA_CRIADA:" + res.id + ":" + res.codigoAcesso);
                                        String querySql = String.format(
                                                "INSERT INTO Pergunta (enunciado,data_inicio,data_fim,codigo_acesso,docente_id) " +
                                                        "VALUES ('%s','%s','%s','%s',%d)",
                                                enunciado.replace("'", "''"), inicio, fim, res.codigoAcesso, sessao.docenteId
                                        );
                                        enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);
                                    }
                                    else if (msg.startsWith("ADICIONAR_OPCAO")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }
                                        String[] p = msg.split(";");
                                        int perguntaId = Integer.parseInt(p[1]);
                                        String letra = p[2];
                                        String texto = p[3];
                                        boolean correta = p[4].equals("1");
                                        db.adicionarOpcao(perguntaId, letra, texto, correta);
                                        db.incrementarVersao();
                                        out.println("OPCAO_ADICIONADA");
                                        String querySql = String.format(
                                                "INSERT INTO Opcao (pergunta_id,letra,texto,is_correta) VALUES (%d,'%s','%s',%d)",
                                                perguntaId, letra, texto.replace("'", "''"), correta ? 1 : 0
                                        );
                                        enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);
                                    }
                                    else if (msg.startsWith("RESPONDER")) {
                                        if (!sessao.autenticado || !"ESTUDANTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }
                                        String[] p = msg.split(";");
                                        int perguntaId = Integer.parseInt(p[1]);
                                        String letra = p[2];

                                        db.guardarResposta(sessao.estudanteId, perguntaId, letra);
                                        db.incrementarVersao();
                                        out.println("RESPOSTA_GUARDADA");
                                        String querySql = String.format(
                                                "INSERT OR REPLACE INTO Resposta (estudante_id,pergunta_id,opcao_letra) VALUES (%d,%d,'%s')",
                                                sessao.estudanteId, perguntaId, letra
                                        );
                                        enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);
                                    }
                                    else if (msg.startsWith("OBTER_PERGUNTA_CODIGO")) {
                                        if (!sessao.autenticado || !"ESTUDANTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA");
                                            continue;
                                        }

                                        String[] p = msg.split(";", 2);
                                        if (p.length < 2) {
                                            out.println("ERRO:ARGS");
                                            continue;
                                        }

                                        String codigo = p[1];

                                        try {
                                            PerguntaDetalhes pd = db.obterPerguntaAtivaPorCodigo(codigo);
                                            if (pd == null) {
                                                out.println("ERRO:CODIGO_INVALIDO");
                                                continue;
                                            }

                                            // tem de estar ATIVA
                                            if (!"ATIVA".equals(pd.estado)) {
                                                out.println("ERRO:PERGUNTA_NAO_ATIVA");
                                                continue;
                                            }

                                            // e ter pelo menos 2 opções
                                            if (pd.opcoes.size() < 2) {
                                                out.println("ERRO:PERGUNTA_INCOMPLETA");
                                                continue;
                                            }

                                            StringBuilder sb = new StringBuilder("PERGUNTA_PARA_RESPONDER:");
                                            sb.append(pd.id).append(";")
                                                    .append(pd.enunciado).append(";")
                                                    .append(pd.dataInicio).append(";")
                                                    .append(pd.dataFim).append(";")
                                                    .append(pd.codigoAcesso);

                                            sb.append("|OPCOES:").append(pd.opcoes.size());
                                            for (var op : pd.opcoes) {
                                                sb.append("|")
                                                        .append(op.letra).append(";")
                                                        .append(op.texto);
                                            }

                                            out.println(sb.toString());

                                        } catch (SQLException e) {
                                            out.println("ERRO:SQL:" + e.getMessage());
                                        }
                                    }

                                    else if (msg.startsWith("REGISTAR_DOCENTE")) {
                                        String[] p = msg.split(";", 5);
                                        if (p.length < 5) { out.println("ERRO:ARGS"); continue; }
                                        String nome = p[1], email = p[2], pass = p[3], codigo = p[4];

                                        try {
                                            if (!db.validarCodigoDocente(codigo)) {
                                                out.println("ERRO:CODIGO_DOCENTE_INVALIDO");
                                                continue;
                                            }

                                            int id = db.criarDocente(nome, email, pass);
                                            db.incrementarVersao();
                                            out.println("DOCENTE_CRIADO:" + id);

                                            String passHash = servidor.db.DatabaseManager.hashPassword(pass);
                                            String q = String.format(
                                                    "INSERT INTO Docente (nome,email,password_hash) VALUES ('%s','%s','%s')",
                                                    nome.replace("'", "''"), email.replace("'", "''"), passHash
                                            );
                                            enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), q);

                                        } catch (SQLException e) {
                                            String m = String.valueOf(e.getMessage());
                                            if (m.contains("UNIQUE")) out.println("ERRO:EMAIL_DUPLICADO");
                                            else out.println("ERRO:SQL");
                                        }
                                    }
                                    else if (msg.startsWith("REGISTAR_ESTUDANTE")) {
                                        String[] p = msg.split(";", 5);
                                        if (p.length < 5) { out.println("ERRO:ARGS"); continue; }

                                        try {
                                            int numero = Integer.parseInt(p[1]);
                                            String nome = p[2], email = p[3], pass = p[4];
                                            int id = db.criarEstudante(numero, nome, email, pass);
                                            db.incrementarVersao();
                                            out.println("ESTUDANTE_CRIADO:" + id);

                                            String passHash = servidor.db.DatabaseManager.hashPassword(pass);
                                            String q = String.format(
                                                    "INSERT INTO Estudante (numero,nome,email,password_hash) VALUES (%d,'%s','%s','%s')",
                                                    numero, nome.replace("'", "''"), email.replace("'", "''"), passHash
                                            );
                                            enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), q);

                                        } catch (NumberFormatException nfe) {
                                            out.println("ERRO:NUMERO_INVALIDO");
                                        } catch (SQLException e) {
                                            String m = String.valueOf(e.getMessage());
                                            if (m.contains("UNIQUE")) out.println("ERRO:EMAIL_OU_NUMERO_DUP");
                                            else out.println("ERRO:SQL");
                                        }
                                    }
                                    else if (msg.startsWith("EDITAR_DOCENTE")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }

                                        String[] p = msg.split(";", 4);
                                        if (p.length < 4) { out.println("ERRO:ARGS"); continue; }

                                        String novoNome  = p[1];
                                        String novoEmail = p[2];
                                        String novaPass  = p[3];

                                        try {
                                            db.atualizarDocentePerfil(sessao.docenteId, novoNome, novoEmail, novaPass);
                                            db.incrementarVersao();
                                            out.println("DOCENTE_ATUALIZADO");

                                            String passHash = servidor.db.DatabaseManager.hashPassword(novaPass);
                                            String q = String.format(
                                                    "UPDATE Docente SET nome='%s', email='%s', password_hash='%s' WHERE id=%d",
                                                    novoNome.replace("'", "''"),
                                                    novoEmail.replace("'", "''"),
                                                    passHash,
                                                    sessao.docenteId
                                            );
                                            enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), q);

                                        } catch (SQLException e) {
                                            String m = String.valueOf(e.getMessage());
                                            if (m.contains("UNIQUE")) out.println("ERRO:EMAIL_DUPLICADO");
                                            else out.println("ERRO:SQL");
                                        }
                                    }

                                    // ===== FASE 2: NOVAS FUNCIONALIDADES DO DOCENTE =====

                                    else if (msg.startsWith("LISTAR_PERGUNTAS")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }

                                        String[] p = msg.split(";", 2);
                                        String filtro = (p.length > 1 && !p[1].trim().isEmpty() && !"TODAS".equalsIgnoreCase(p[1])) ? p[1] : null;

                                        try {
                                            var perguntas = db.listarPerguntas(sessao.docenteId, filtro);

                                            if (perguntas.isEmpty()) {
                                                out.println("INFO:NENHUMA_PERGUNTA_ENCONTRADA");
                                            } else {
                                                StringBuilder sb = new StringBuilder("PERGUNTAS_LISTA:" + perguntas.size());
                                                for (var pg : perguntas) {
                                                    sb.append("|").append(pg.id)
                                                            .append(";").append(pg.enunciado)
                                                            .append(";").append(pg.dataInicio)
                                                            .append(";").append(pg.dataFim)
                                                            .append(";").append(pg.codigoAcesso)
                                                            .append(";").append(pg.estado)
                                                            .append(";").append(pg.numRespostas);
                                                }
                                                out.println(sb.toString());
                                            }
                                        } catch (SQLException e) {
                                            out.println("ERRO:SQL:" + e.getMessage());
                                        }
                                    }

                                    else if (msg.startsWith("EDITAR_PERGUNTA")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }

                                        String[] p = msg.split(";", 5);
                                        if (p.length < 5) { out.println("ERRO:ARGS"); continue; }

                                        try {
                                            int perguntaId = Integer.parseInt(p[1]);
                                            String novoEnunciado = p[2];
                                            String novoInicio = p[3];
                                            String novoFim = p[4];

                                            if (!db.perguntaPertenceADocente(perguntaId, sessao.docenteId)) {
                                                out.println("ERRO:NAO_PERTENCE");
                                                continue;
                                            }

                                            db.editarPergunta(perguntaId, novoEnunciado, novoInicio, novoFim);
                                            db.incrementarVersao();
                                            out.println("PERGUNTA_EDITADA");

                                            String querySql = String.format(
                                                    "UPDATE Pergunta SET enunciado='%s', data_inicio='%s', data_fim='%s' WHERE id=%d",
                                                    novoEnunciado.replace("'", "''"), novoInicio, novoFim, perguntaId
                                            );
                                            enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql);

                                        } catch (NumberFormatException nfe) {
                                            out.println("ERRO:ID_INVALIDO");
                                        } catch (SQLException e) {
                                            if (e.getMessage().contains("já tem respostas")) {
                                                out.println("ERRO:TEM_RESPOSTAS");
                                            } else {
                                                out.println("ERRO:SQL:" + e.getMessage());
                                            }
                                        }
                                    }

                                    else if (msg.startsWith("ELIMINAR_PERGUNTA")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }

                                        String[] p = msg.split(";", 2);
                                        if (p.length < 2) { out.println("ERRO:ARGS"); continue; }

                                        try {
                                            int perguntaId = Integer.parseInt(p[1]);

                                            if (!db.perguntaPertenceADocente(perguntaId, sessao.docenteId)) {
                                                out.println("ERRO:NAO_PERTENCE");
                                                continue;
                                            }

                                            db.eliminarPergunta(perguntaId);
                                            db.incrementarVersao();
                                            out.println("PERGUNTA_ELIMINADA");

                                            String querySql1 = String.format("DELETE FROM Opcao WHERE pergunta_id=%d", perguntaId);
                                            String querySql2 = String.format("DELETE FROM Pergunta WHERE id=%d", perguntaId);

                                            enviarHeartbeatComQuery(socket, grupoMulticast, db.getVersao(), querySql1 + ";" + querySql2);

                                        } catch (NumberFormatException nfe) {
                                            out.println("ERRO:ID_INVALIDO");
                                        } catch (SQLException e) {
                                            if (e.getMessage().contains("já tem respostas")) {
                                                out.println("ERRO:TEM_RESPOSTAS");
                                            } else {
                                                out.println("ERRO:SQL:" + e.getMessage());
                                            }
                                        }
                                    }

                                    else if (msg.startsWith("VER_RESULTADOS")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }

                                        String[] p = msg.split(";", 2);
                                        if (p.length < 2) { out.println("ERRO:ARGS"); continue; }

                                        try {
                                            int perguntaId = Integer.parseInt(p[1]);

                                            PerguntaDetalhes pd = db.obterDetalhesPerguntaExpirada(perguntaId, sessao.docenteId);

                                            StringBuilder sb = new StringBuilder("RESULTADOS:");

                                            sb.append(pd.id).append(";")
                                                    .append(pd.enunciado).append(";")
                                                    .append(pd.dataInicio).append(";")
                                                    .append(pd.dataFim).append(";")
                                                    .append(pd.codigoAcesso).append(";")
                                                    .append(pd.numRespostas);

                                            sb.append("|OPCOES:").append(pd.opcoes.size());
                                            for (var op : pd.opcoes) {
                                                sb.append("|").append(op.letra)
                                                        .append(";").append(op.texto)
                                                        .append(";").append(op.isCorreta ? "1" : "0")
                                                        .append(";").append(op.numRespostas);
                                            }

                                            sb.append("|RESPOSTAS:").append(pd.respostas.size());
                                            for (var resp : pd.respostas) {
                                                sb.append("|").append(resp.estudanteNumero)
                                                        .append(";").append(resp.estudanteNome)
                                                        .append(";").append(resp.estudanteEmail)
                                                        .append(";").append(resp.opcaoLetra)
                                                        .append(";").append(resp.estaCorreta ? "CERTA" : "ERRADA")
                                                        .append(";").append(resp.dataHora);
                                            }

                                            out.println(sb.toString());

                                        } catch (NumberFormatException nfe) {
                                            out.println("ERRO:ID_INVALIDO");
                                        } catch (SQLException e) {
                                            if (e.getMessage().contains("não pertence")) {
                                                out.println("ERRO:NAO_PERTENCE");
                                            } else if (e.getMessage().contains("não expirou")) {
                                                out.println("ERRO:NAO_EXPIRADA");
                                            } else {
                                                out.println("ERRO:SQL:" + e.getMessage());
                                            }
                                        }
                                    }

                                    else if (msg.startsWith("EXPORTAR_CSV")) {
                                        if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                                            out.println("ERRO: PERMISSAO_NEGADA"); continue;
                                        }

                                        String[] p = msg.split(";", 2);
                                        if (p.length < 2) { out.println("ERRO:ARGS"); continue; }

                                        try {
                                            int perguntaId = Integer.parseInt(p[1]);

                                            String csv = db.exportarParaCSV(perguntaId, sessao.docenteId);

                                            String csvBase64 = java.util.Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                                            out.println("CSV_EXPORTADO:" + csvBase64);

                                        } catch (NumberFormatException nfe) {
                                            out.println("ERRO:ID_INVALIDO");
                                        } catch (SQLException e) {
                                            if (e.getMessage().contains("não pertence")) {
                                                out.println("ERRO:NAO_PERTENCE");
                                            } else if (e.getMessage().contains("não expirou")) {
                                                out.println("ERRO:NAO_EXPIRADA");
                                            } else {
                                                out.println("ERRO:SQL:" + e.getMessage());
                                            }
                                        }
                                    }
                                    else if ("LOGOUT".equals(msg)) {
                                        sessao.autenticado = false;
                                        sessao.role = null;
                                        sessao.docenteId = null;
                                        sessao.estudanteId = null;
                                        out.println("LOGOUT_OK");
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