package servidor.handlers;

import servidor.ReplicationSender;
import servidor.db.DatabaseManager;
import servidor.db.PerguntaDetalhes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ClienteHandler implements Runnable {

    private final Socket cliente;
    private final DatabaseManager db;
    private final ReplicationSender replicator;

    // Conjunto de clientes autenticados para notificações assíncronas
    private static final Set<PrintWriter> clientesNotificaveis =
            Collections.synchronizedSet(new HashSet<>());

    public ClienteHandler(Socket cliente, DatabaseManager db, ReplicationSender replicator) {
        this.cliente = cliente;
        this.db = db;
        this.replicator = replicator;
    }

    private static class Sessao {
        boolean autenticado = false;
        String role = null;         // "DOCENTE" ou "ESTUDANTE"
        Integer docenteId = null;
        Integer estudanteId = null;
    }

    private void registarClienteParaNotificacoes(PrintWriter out) {
        clientesNotificaveis.add(out);
    }

    private void removerClienteDeNotificacoes(PrintWriter out) {
        clientesNotificaveis.remove(out);
    }

    public static void enviarNotificacaoATodos(String msg) {
        synchronized (clientesNotificaveis) {
            for (PrintWriter pw : clientesNotificaveis) {
                pw.println("NOTIF:" + msg);
            }
        }
    }

    @Override
    public void run() {
        System.out.println("[Servidor] Cliente conectado: " + cliente.getInetAddress().getHostAddress());
        Sessao sessao = new Sessao();
        PrintWriter out = null;

        try (BufferedReader in = new BufferedReader(new InputStreamReader(cliente.getInputStream()));
             PrintWriter pw = new PrintWriter(cliente.getOutputStream(), true)) {

            out = pw;

            // 1) Timeout só para a PRIMEIRA mensagem (credenciais)
            cliente.setSoTimeout(30_000);

            String msgInicial;
            try {
                msgInicial = in.readLine();
            } catch (SocketTimeoutException ste) {
                System.out.println("[Servidor] Cliente não enviou credenciais em 30s, a fechar ligação.");
                return;
            }

            if (msgInicial == null) {
                System.out.println("[Servidor] Cliente fechou ligação antes de autenticar.");
                return;
            }

            System.out.println("[Servidor] Recebido do cliente (1ª msg): " + msgInicial);

            if (!(msgInicial.startsWith("LOGIN_DOCENTE")
                    || msgInicial.startsWith("LOGIN_ESTUDANTE")
                    || msgInicial.startsWith("REGISTAR_DOCENTE")
                    || msgInicial.startsWith("REGISTAR_ESTUDANTE"))) {
                out.println("ERRO:AUTENTICACAO_OBRIGATORIA");
                return;
            }

            processarMensagem(msgInicial, sessao, in, out);

            if ((msgInicial.startsWith("LOGIN_DOCENTE") || msgInicial.startsWith("LOGIN_ESTUDANTE"))
                    && !sessao.autenticado) {
                return;
            }

            cliente.setSoTimeout(0);

            String msg;
            while ((msg = in.readLine()) != null) {
                System.out.println("[Servidor] Recebido do cliente: " + msg);
                processarMensagem(msg, sessao, in, out);
            }

            System.out.println("[Servidor] Cliente desligou.");
        } catch (Exception e) {
            System.err.println("[Servidor] Erro ao processar cliente: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (out != null) {
                removerClienteDeNotificacoes(out);
            }
            try { cliente.close(); } catch (Exception ignore) {}
        }
    }


    private void processarMensagem(String msg, Sessao sessao, BufferedReader in, PrintWriter out) {
        try {
            if (msg.startsWith("LOGIN_DOCENTE")) {
                String[] p = msg.split(";");
                String email = p[1], pass = p[2];
                boolean ok = db.autenticarDocente(email, pass);
                if (ok) {
                    sessao.autenticado = true;
                    sessao.role = "DOCENTE";
                    sessao.docenteId = db.getDocenteId(email);
                    registarClienteParaNotificacoes(out);
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
                    registarClienteParaNotificacoes(out);
                    out.println("LOGIN_OK");
                } else out.println("LOGIN_FAIL");
            }

            else if (msg.startsWith("CRIAR_PERGUNTA")) {
                if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";");

                synchronized (db) {
                    String enunciado, inicio, fim;
                    if (p.length == 5) {
                        enunciado = p[2];
                        inicio = p[3];
                        fim = p[4];
                    } else {
                        enunciado = p[1];
                        inicio = p[2];
                        fim = p[3];
                    }

                    var res = db.criarPerguntaCompleta(sessao.docenteId, enunciado, inicio, fim);
                    int versao = db.incrementarVersao();
                    out.println("PERGUNTA_CRIADA:" + res.id + ":" + res.codigoAcesso);

                    String querySql = String.format(
                            "INSERT INTO Pergunta (enunciado,data_inicio,data_fim,codigo_acesso,docente_id) " +
                                    "VALUES ('%s','%s','%s','%s',%d)",
                            enunciado.replace("'", "''"), inicio, fim, res.codigoAcesso, sessao.docenteId
                    );
                    replicator.sendUpdate(versao, querySql);
                    enviarNotificacaoATodos("PERGUNTAS_ATUALIZADAS");
                }
            }
            else if (msg.startsWith("ADICIONAR_OPCAO")) {
                if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";");

                synchronized (db) {
                    int perguntaId = Integer.parseInt(p[1]);
                    String letra = p[2];
                    String texto = p[3];
                    boolean correta = p[4].equals("1");

                    db.adicionarOpcao(perguntaId, letra, texto, correta);
                    int versao = db.incrementarVersao();
                    out.println("OPCAO_ADICIONADA");

                    String querySql = String.format(
                            "INSERT INTO Opcao (pergunta_id,letra,texto,is_correta) VALUES (%d,'%s','%s',%d)",
                            perguntaId, letra, texto.replace("'", "''"), correta ? 1 : 0
                    );
                    replicator.sendUpdate(versao, querySql);
                    enviarNotificacaoATodos("PERGUNTAS_ATUALIZADAS");
                }
            }

            else if (msg.startsWith("RESPONDER")) {
                if (!sessao.autenticado || !"ESTUDANTE".equals(sessao.role)) {
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";");
                if (p.length < 3) {
                    out.println("ERRO:ARGS");
                    return;
                }

                int perguntaId;
                try {
                    perguntaId = Integer.parseInt(p[1]);
                } catch (NumberFormatException nfe) {
                    out.println("ERRO:ID_INVALIDO");
                    return;
                }

                String letra = p[2];
                try {
                    synchronized (db) {
                        db.guardarResposta(sessao.estudanteId, perguntaId, letra);
                        int versao = db.incrementarVersao();
                        out.println("RESPOSTA_GUARDADA");

                        String querySql = String.format(
                                "INSERT INTO Resposta (estudante_id,pergunta_id,opcao_letra) VALUES (%d,%d,'%s')",
                                sessao.estudanteId, perguntaId, letra
                        );
                        replicator.sendUpdate(versao, querySql);
                        enviarNotificacaoATodos("RESPOSTAS_ATUALIZADAS");
                    }
                } catch (SQLException e) {
                    String m = e.getMessage() != null ? e.getMessage() : "";
                    if (m.contains("UNIQUE")) out.println("ERRO:JA_RESPONDEU");
                    else out.println("ERRO:SQL");
                }
            }
            else if (msg.startsWith("OBTER_PERGUNTA_CODIGO")) {
                if (!sessao.autenticado || !"ESTUDANTE".equals(sessao.role)) { out.println("ERRO: PERMISSAO_NEGADA"); return; }
                String[] p = msg.split(";", 2);
                if (p.length < 2) { out.println("ERRO:ARGS"); return; }
                String codigo = p[1];

                try {
                    PerguntaDetalhes pd = db.obterPerguntaAtivaPorCodigo(codigo);
                    if (pd == null) { out.println("ERRO:CODIGO_INVALIDO"); return; }
                    if (!"ATIVA".equals(pd.estado)) { out.println("ERRO:PERGUNTA_NAO_ATIVA"); return; }
                    if (pd.opcoes.size() < 2) { out.println("ERRO:PERGUNTA_INCOMPLETA"); return; }

                    StringBuilder sb = new StringBuilder("PERGUNTA_PARA_RESPONDER:");
                    sb.append(pd.id).append(";")
                            .append(pd.enunciado).append(";")
                            .append(pd.dataInicio).append(";")
                            .append(pd.dataFim).append(";")
                            .append(pd.codigoAcesso);

                    sb.append("|OPCOES:").append(pd.opcoes.size());
                    for (var op : pd.opcoes) {
                        sb.append("|").append(op.letra).append(";").append(op.texto);
                    }
                    out.println(sb.toString());
                } catch (SQLException e) {
                    out.println("ERRO:SQL:" + e.getMessage());
                }
            }

            else if (msg.startsWith("REGISTAR_DOCENTE")) {
                String[] p = msg.split(";", 5);
                if (p.length < 5) { out.println("ERRO:ARGS"); return; }
                String nome = p[1], email = p[2], pass = p[3], codigo = p[4].trim();

                try {
                    if (!db.validarCodigoDocente(codigo)) {
                        out.println("ERRO:CODIGO_DOCENTE_INVALIDO");
                        return;
                    }

                    synchronized (db) {
                        int id = db.criarDocente(nome, email, pass);
                        int versao = db.incrementarVersao();
                        out.println("DOCENTE_CRIADO:" + id);

                        String passHash = DatabaseManager.hashPassword(pass);
                        String q = String.format(
                                "INSERT INTO Docente (nome,email,password_hash) VALUES ('%s','%s','%s')",
                                nome.replace("'", "''"), email.replace("'", "''"), passHash
                        );
                        replicator.sendUpdate(versao, q);
                        enviarNotificacaoATodos("UTILIZADORES_ATUALIZADOS");
                    }
                } catch (SQLException e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("UNIQUE")) out.println("ERRO:EMAIL_DUPLICADO");
                    else out.println("ERRO:SQL");
                }
            }
            else if (msg.startsWith("REGISTAR_ESTUDANTE")) {
                String[] p = msg.split(";", 5);
                if (p.length < 5) { out.println("ERRO:ARGS"); return; }
                try {
                    int numero = Integer.parseInt(p[1]);
                    String nome = p[2], email = p[3], pass = p[4];

                    synchronized (db) {
                        int id = db.criarEstudante(numero, nome, email, pass);
                        int versao = db.incrementarVersao();
                        out.println("ESTUDANTE_CRIADO:" + id);

                        String passHash = DatabaseManager.hashPassword(pass);
                        String q = String.format(
                                "INSERT INTO Estudante (numero,nome,email,password_hash) VALUES (%d,'%s','%s','%s')",
                                numero, nome.replace("'", "''"), email.replace("'", "''"), passHash
                        );
                        replicator.sendUpdate(versao, q);
                        enviarNotificacaoATodos("UTILIZADORES_ATUALIZADOS");
                    }
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
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";", 4);
                if (p.length < 4) { out.println("ERRO:ARGS"); return; }

                String novoNome  = p[1];
                String novoEmail = p[2];
                String novaPass  = p[3];

                try {
                    synchronized (db) {
                        db.atualizarDocentePerfil(sessao.docenteId, novoNome, novoEmail, novaPass);
                        int versao = db.incrementarVersao();
                        out.println("DOCENTE_ATUALIZADO");

                        String passHash = DatabaseManager.hashPassword(novaPass);
                        String q = String.format(
                                "UPDATE Docente SET nome='%s', email='%s', password_hash='%s' WHERE id=%d",
                                novoNome.replace("'", "''"), novoEmail.replace("'", "''"), passHash, sessao.docenteId
                        );
                        replicator.sendUpdate(versao, q);
                        enviarNotificacaoATodos("UTILIZADORES_ATUALIZADOS");
                    }
                } catch (SQLException e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("UNIQUE")) out.println("ERRO:EMAIL_DUPLICADO");
                    else out.println("ERRO:SQL");
                }
            }
            else if (msg.startsWith("EDITAR_ESTUDANTE")) {
                if (!sessao.autenticado || !"ESTUDANTE".equals(sessao.role)) {
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";", 4);
                if (p.length < 4) { out.println("ERRO:ARGS"); return; }

                String novoNome  = p[1];
                String novoEmail = p[2];
                String novaPass  = p[3];

                try {
                    synchronized (db) {
                        db.atualizarEstudantePerfil(sessao.estudanteId, novoNome, novoEmail, novaPass);
                        int versao = db.incrementarVersao();
                        out.println("ESTUDANTE_ATUALIZADO");

                        String passHash = DatabaseManager.hashPassword(novaPass);
                        String q = String.format(
                                "UPDATE Estudante SET nome='%s', email='%s', password_hash='%s' WHERE id=%d",
                                novoNome.replace("'", "''"), novoEmail.replace("'", "''"), passHash, sessao.estudanteId
                        );
                        replicator.sendUpdate(versao, q);
                        enviarNotificacaoATodos("UTILIZADORES_ATUALIZADOS");
                    }
                } catch (SQLException e) {
                    String m = e.getMessage() != null ? e.getMessage() : "";
                    if (m.contains("UNIQUE")) out.println("ERRO:EMAIL_DUPLICADO");
                    else out.println("ERRO:SQL");
                }
            }

            else if (msg.startsWith("LISTAR_PERGUNTAS")) {
                if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) { out.println("ERRO: PERMISSAO_NEGADA"); return; }
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
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";", 5);
                if (p.length < 5) { out.println("ERRO:ARGS"); return; }

                try {
                    int perguntaId = Integer.parseInt(p[1]);
                    String novoEnunciado = p[2];
                    String novoInicio = p[3];
                    String novoFim = p[4];

                    if (!db.perguntaPertenceADocente(perguntaId, sessao.docenteId)) {
                        out.println("ERRO:NAO_PERTENCE");
                        return;
                    }

                    synchronized (db) {
                        db.editarPergunta(perguntaId, novoEnunciado, novoInicio, novoFim);
                        int versao = db.incrementarVersao();
                        out.println("PERGUNTA_EDITADA");

                        String querySql = String.format(
                                "UPDATE Pergunta SET enunciado='%s', data_inicio='%s', data_fim='%s' WHERE id=%d",
                                novoEnunciado.replace("'", "''"), novoInicio, novoFim, perguntaId
                        );
                        replicator.sendUpdate(versao, querySql);
                        enviarNotificacaoATodos("PERGUNTAS_ATUALIZADAS");
                    }
                } catch (NumberFormatException nfe) {
                    out.println("ERRO:ID_INVALIDO");
                } catch (SQLException e) {
                    if (e.getMessage().contains("já tem respostas")) out.println("ERRO:TEM_RESPOSTAS");
                    else out.println("ERRO:SQL:" + e.getMessage());
                }
            }
            else if (msg.startsWith("ELIMINAR_PERGUNTA")) {
                if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) {
                    out.println("ERRO: PERMISSAO_NEGADA");
                    return;
                }
                String[] p = msg.split(";", 2);
                if (p.length < 2) { out.println("ERRO:ARGS"); return; }

                try {
                    int perguntaId = Integer.parseInt(p[1]);
                    if (!db.perguntaPertenceADocente(perguntaId, sessao.docenteId)) {
                        out.println("ERRO:NAO_PERTENCE");
                        return;
                    }

                    synchronized (db) {
                        db.eliminarPergunta(perguntaId);
                        out.println("PERGUNTA_ELIMINADA");

                        String q1 = String.format("DELETE FROM Opcao WHERE pergunta_id=%d", perguntaId);
                        int v1 = db.incrementarVersao();
                        replicator.sendUpdate(v1, q1);

                        String q2 = String.format("DELETE FROM Pergunta WHERE id=%d", perguntaId);
                        int v2 = db.incrementarVersao();
                        replicator.sendUpdate(v2, q2);

                        enviarNotificacaoATodos("PERGUNTAS_ATUALIZADAS");
                    }

                } catch (NumberFormatException nfe) {
                    out.println("ERRO:ID_INVALIDO");
                } catch (SQLException e) {
                    if (e.getMessage().contains("já tem respostas")) out.println("ERRO:TEM_RESPOSTAS");
                    else out.println("ERRO:SQL:" + e.getMessage());
                }
            }
            else if (msg.startsWith("VER_RESULTADOS")) {
                if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) { out.println("ERRO: PERMISSAO_NEGADA"); return; }
                String[] p = msg.split(";", 2);
                if (p.length < 2) { out.println("ERRO:ARGS"); return; }

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
                    if (e.getMessage().contains("não pertence")) out.println("ERRO:NAO_PERTENCE");
                    else if (e.getMessage().contains("não expirou")) out.println("ERRO:NAO_EXPIRADA");
                    else out.println("ERRO:SQL:" + e.getMessage());
                }
            }
            else if (msg.startsWith("EXPORTAR_CSV")) {
                if (!sessao.autenticado || !"DOCENTE".equals(sessao.role)) { out.println("ERRO: PERMISSAO_NEGADA"); return; }
                String[] p = msg.split(";", 2);
                if (p.length < 2) { out.println("ERRO:ARGS"); return; }

                try {
                    int perguntaId = Integer.parseInt(p[1]);
                    String csv = db.exportarParaCSV(perguntaId, sessao.docenteId);
                    String csvBase64 = java.util.Base64.getEncoder().encodeToString(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.println("CSV_EXPORTADO:" + csvBase64);
                } catch (NumberFormatException nfe) {
                    out.println("ERRO:ID_INVALIDO");
                } catch (SQLException e) {
                    if (e.getMessage().contains("não pertence")) out.println("ERRO:NAO_PERTENCE");
                    else if (e.getMessage().contains("não expirou")) out.println("ERRO:NAO_EXPIRADA");
                    else out.println("ERRO:SQL:" + e.getMessage());
                }
            }

            else if (msg.startsWith("LISTAR_RESPOSTAS_ESTUDANTE")) {
                if (!sessao.autenticado || !"ESTUDANTE".equals(sessao.role)) { out.println("ERRO: PERMISSAO_NEGADA"); return; }
                try {
                    var lista = db.listarRespostasEstudanteExpiradas(sessao.estudanteId);
                    if (lista.isEmpty()) out.println("INFO:NENHUMA_RESPOSTA");
                    else {
                        StringBuilder sb = new StringBuilder("RESPOSTAS_ESTUDANTE:" + lista.size());
                        for (var rInfo : lista) {
                            sb.append("|")
                                    .append(rInfo.perguntaId).append(";")
                                    .append(rInfo.enunciado).append(";")
                                    .append(rInfo.dataFim).append(";")
                                    .append(rInfo.dataResposta).append(";")
                                    .append(rInfo.letra).append(";")
                                    .append(rInfo.correta ? "CERTA" : "ERRADA");
                        }
                        out.println(sb.toString());
                    }
                } catch (SQLException e) {
                    out.println("ERRO:SQL:" + e.getMessage());
                }
            }

            else if ("LOGOUT".equals(msg)) {
                sessao.autenticado = false;
                sessao.role = null;
                sessao.docenteId = null;
                sessao.estudanteId = null;
                removerClienteDeNotificacoes(out);
                out.println("LOGOUT_OK");
            }
            else {
                out.println("COMANDO_DESCONHECIDO");
            }
        } catch (Exception e) {
            System.err.println("[Servidor] Erro ao processar mensagem: " + e.getMessage());
            out.println("ERRO:INTERNO");
        }
    }

    private static int getEstudanteId(DatabaseManager db, String email) throws SQLException {
        try (PreparedStatement ps = db.getConnection().prepareStatement("SELECT id FROM Estudante WHERE email=?")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }
}