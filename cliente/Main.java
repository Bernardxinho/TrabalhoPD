package cliente;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Main {
    private static final String DEF_IP_DIR = "127.0.0.1";
    private static final int DEF_PORT_DIR = 4000;
    private static final int TIMEOUT_RECONEXAO_MS = 20000;

    private static class Credenciais {
        String tipo;
        String email;
        String password;
        boolean autenticado = false;

        public Credenciais(String tipo, String email, String password) {
            this.tipo = tipo;
            this.email = email;
            this.password = password;
        }

        public String getComandoLogin() {
            return tipo.equals("DOCENTE")
                    ? "LOGIN_DOCENTE;" + email + ";" + password
                    : "LOGIN_ESTUDANTE;" + email + ";" + password;
        }
    }

    public static void main(String[] args) {
        String ipDiretoria = (args.length >= 1) ? args[0] : DEF_IP_DIR;
        int portoDiretoria = (args.length >= 2) ? Integer.parseInt(args[1]) : DEF_PORT_DIR;

        Scanner sc = new Scanner(System.in);
        Credenciais credenciais = null;
        String ultimoServidorIp = null;
        int ultimoServidorPorto = -1;

        while (true) {
            Socket socketTCP = null;
            BufferedReader in = null;
            PrintWriter out = null;

            try {
                String[] hp = pedirServidorPrincipal(ipDiretoria, portoDiretoria, 3000, 3);
                String ipServidor = hp[0];
                int portoServidor = Integer.parseInt(hp[1]);

                System.out.printf("[Cliente] Servidor principal obtido: %s:%d%n", ipServidor, portoServidor);

                socketTCP = new Socket();
                socketTCP.connect(new InetSocketAddress(ipServidor, portoServidor), 4000);
                socketTCP.setSoTimeout(30000);
                System.out.println("[Cliente] ✓ Ligado ao servidor via TCP!");

                in = new BufferedReader(new InputStreamReader(socketTCP.getInputStream()));
                out = new PrintWriter(socketTCP.getOutputStream(), true);

                if (credenciais != null && credenciais.autenticado) {
                    System.out.println("[Cliente] Re-autenticando...");
                    out.println(credenciais.getComandoLogin());
                    String respAuth = lerResposta(in);

                    if ("LOGIN_OK".equals(respAuth)) {
                        System.out.println("[Cliente] ✓ Re-autenticação bem-sucedida!");
                        credenciais.autenticado = true;
                        socketTCP.setSoTimeout(0);
                    } else {
                        System.out.println("[Cliente] ✗ Falha na re-autenticação. Faça login novamente.");
                        credenciais.autenticado = false;
                    }
                }

                ultimoServidorIp = ipServidor;
                ultimoServidorPorto = portoServidor;

                while (true) {
                    mostrarMenu();
                    System.out.print("> ");
                    String op = sc.nextLine().trim();

                    if (op.equals("0")) {
                        System.out.println("[Cliente] A terminar...");
                        return;
                    }

                    String wire = null;

                    switch (op) {
                        case "1": {
                            System.out.print("Email docente: ");
                            String email = sc.nextLine().trim();
                            System.out.print("Password: ");
                            String pass  = sc.nextLine().trim();
                            wire = "LOGIN_DOCENTE;" + email + ";" + pass;

                            credenciais = new Credenciais("DOCENTE", email, pass);
                            break;
                        }
                        case "2": {
                            System.out.print("Email estudante: ");
                            String email = sc.nextLine().trim();
                            System.out.print("Password: ");
                            String pass  = sc.nextLine().trim();
                            wire = "LOGIN_ESTUDANTE;" + email + ";" + pass;

                            credenciais = new Credenciais("ESTUDANTE", email, pass);
                            break;
                        }
                        case "3": {
                            System.out.print("Enunciado: ");
                            String enun  = sc.nextLine().trim();
                            System.out.print("Início (AAAA-MM-DD HH:mm): ");
                            String ini   = sc.nextLine().trim();
                            System.out.print("Fim    (AAAA-MM-DD HH:mm): ");
                            String fim   = sc.nextLine().trim();
                            wire = "CRIAR_PERGUNTA;" + enun + ";" + ini + ";" + fim;
                            break;
                        }
                        case "4": {
                            System.out.print("Pergunta ID: ");
                            String pid   = sc.nextLine().trim();
                            System.out.print("Letra (a/b/c): ");
                            String letra = sc.nextLine().trim();
                            if (!letra.isEmpty()) letra = letra.substring(0,1).toLowerCase();
                            System.out.print("Texto da opção: ");
                            String txt  = sc.nextLine().trim();
                            System.out.print("É correta? (1/0): ");
                            String ok = sc.nextLine().trim();
                            ok = "1".equals(ok) ? "1" : "0";
                            wire = "ADICIONAR_OPCAO;" + pid + ";" + letra + ";" + txt + ";" + ok;
                            break;
                        }
                        case "5": {
                            System.out.print("Código da pergunta: ");
                            String codigo = sc.nextLine().trim();
                            wire = "OBTER_PERGUNTA_CODIGO;" + codigo;
                            break;
                        }
                        case "6": {
                            System.out.print("Nome: ");
                            String nome = sc.nextLine().trim();
                            System.out.print("Email: ");
                            String email = sc.nextLine().trim();
                            System.out.print("Password: ");
                            String pass = sc.nextLine().trim();
                            System.out.print("Código docente: ");
                            String cod = sc.nextLine().trim();
                            wire = "REGISTAR_DOCENTE;" + nome + ";" + email + ";" + pass + ";" + cod;
                            break;
                        }
                        case "7": {
                            System.out.print("Número: ");
                            String num  = sc.nextLine().trim();
                            System.out.print("Nome: ");
                            String nome = sc.nextLine().trim();
                            System.out.print("Email: ");
                            String email= sc.nextLine().trim();
                            System.out.print("Password: ");
                            String pass = sc.nextLine().trim();
                            wire = "REGISTAR_ESTUDANTE;" + num + ";" + nome + ";" + email + ";" + pass;
                            break;
                        }
                        case "8": {
                            System.out.println("\n--- Filtrar por estado ---");
                            System.out.println("  1) Todas");
                            System.out.println("  2) Ativas");
                            System.out.println("  3) Futuras");
                            System.out.println("  4) Expiradas");
                            System.out.print("Opção: ");
                            String filtroOp = sc.nextLine().trim();

                            String filtro = "TODAS";
                            switch (filtroOp) {
                                case "2": filtro = "ATIVA"; break;
                                case "3": filtro = "FUTURA"; break;
                                case "4": filtro = "EXPIRADA"; break;
                            }
                            wire = "LISTAR_PERGUNTAS;" + filtro;
                            break;
                        }
                        case "9": {
                            System.out.print("ID da pergunta: ");
                            String pid = sc.nextLine().trim();
                            System.out.print("Novo enunciado: ");
                            String enun = sc.nextLine().trim();
                            System.out.print("Novo início (AAAA-MM-DD HH:mm): ");
                            String ini = sc.nextLine().trim();
                            System.out.print("Novo fim (AAAA-MM-DD HH:mm): ");
                            String fim = sc.nextLine().trim();
                            wire = "EDITAR_PERGUNTA;" + pid + ";" + enun + ";" + ini + ";" + fim;
                            break;
                        }
                        case "10": {
                            System.out.print("ID da pergunta a eliminar: ");
                            String pid = sc.nextLine().trim();
                            System.out.print("Tem a certeza? (S/N): ");
                            String confirma = sc.nextLine().trim();
                            if (confirma.equalsIgnoreCase("S")) {
                                wire = "ELIMINAR_PERGUNTA;" + pid;
                            } else {
                                System.out.println("[Cliente] Operação cancelada.");
                                continue;
                            }
                            break;
                        }
                        case "11": {
                            System.out.print("ID da pergunta expirada: ");
                            String pid = sc.nextLine().trim();
                            wire = "VER_RESULTADOS;" + pid;
                            break;
                        }
                        case "12": {
                            System.out.print("ID da pergunta a exportar: ");
                            String pid = sc.nextLine().trim();
                            wire = "EXPORTAR_CSV;" + pid;
                            break;
                        }
                        case "13": {
                            System.out.print("Novo nome: ");
                            String nome = sc.nextLine().trim();
                            System.out.print("Novo email: ");
                            String email = sc.nextLine().trim();
                            System.out.print("Nova password: ");
                            String pass = sc.nextLine().trim();
                            wire = "EDITAR_DOCENTE;" + nome + ";" + email + ";" + pass;
                            break;
                        }
                        case "14": {
                            wire = "LOGOUT";
                            if (credenciais != null) {
                                credenciais.autenticado = false;
                            }
                            break;
                        }
                        case "15": {
                            System.out.print("Novo nome: ");
                            String nome = sc.nextLine().trim();
                            System.out.print("Novo email: ");
                            String email = sc.nextLine().trim();
                            System.out.print("Nova password: ");
                            String pass = sc.nextLine().trim();
                            wire = "EDITAR_ESTUDANTE;" + nome + ";" + email + ";" + pass;
                            break;
                        }
                        case "16": {
                            wire = "LISTAR_RESPOSTAS_ESTUDANTE";
                            break;
                        }
                        default:
                            System.out.println("[Cliente] Opção inválida.");
                            continue;
                    }

                    if (wire != null && !wire.isEmpty()) {
                        out.println(wire);
                        String resp = lerResposta(in);

                        if (resp == null) {
                            System.out.println("[Cliente] ✗ Ligação fechada pelo servidor.");
                            throw new IOException("Ligação perdida");
                        }

                        if ((wire.startsWith("LOGIN_DOCENTE") || wire.startsWith("LOGIN_ESTUDANTE"))
                                && "LOGIN_OK".equals(resp)) {
                            credenciais.autenticado = true;
                            socketTCP.setSoTimeout(0);
                        }

                        if (wire.startsWith("OBTER_PERGUNTA_CODIGO") &&
                                resp.startsWith("PERGUNTA_PARA_RESPONDER:")) {
                            processarPerguntaParaResponder(resp, sc, out, in);
                            continue;
                        }

                        if (wire.startsWith("LISTAR_PERGUNTAS") && resp.startsWith("PERGUNTAS_LISTA:")) {
                            imprimirListaPerguntas(resp);
                        } else if (wire.startsWith("VER_RESULTADOS") && resp.startsWith("RESULTADOS:")) {
                            imprimirResultados(resp);
                        } else if (wire.startsWith("LISTAR_RESPOSTAS_ESTUDANTE") &&
                                resp.startsWith("RESPOSTAS_ESTUDANTE:")) {
                            imprimirRespostasEstudante(resp);
                        } else if (wire.startsWith("EXPORTAR_CSV") && resp.startsWith("CSV_EXPORTADO:")) {
                            exportarCSV(resp, wire);
                        } else if (resp.startsWith("INFO:")) {
                            String code = resp.substring("INFO:".length());
                            switch (code) {
                                case "NENHUMA_PERGUNTA_ENCONTRADA" ->
                                        System.out.println("[Cliente] Não existe nenhuma pergunta para o filtro selecionado.");
                                case "NENHUMA_RESPOSTA" ->
                                        System.out.println("[Cliente] Ainda não tem respostas a perguntas expiradas.");
                                default ->
                                        System.out.println("[Cliente] [INFO] " + code);
                            }
                        } else {
                            System.out.println("[Cliente] " + resp);
                        }
                    }
                }

            } catch (SocketTimeoutException te) {
                System.err.println("[Cliente] Timeout na ligação.");
                tratarReconexao(ipDiretoria, portoDiretoria, ultimoServidorIp, ultimoServidorPorto);

            } catch (IOException ioe) {
                System.err.println("[Cliente] ✗ Falha de ligação: " + ioe.getMessage());
                tratarReconexao(ipDiretoria, portoDiretoria, ultimoServidorIp, ultimoServidorPorto);

            } catch (Exception e) {
                System.err.println("[Cliente] Erro: " + e.getMessage());
                e.printStackTrace();
                break;

            } finally {
                fecharQuietamente(in);
                fecharQuietamente(out);
                fecharQuietamente(socketTCP);
            }
        }
    }

    private static void tratarReconexao(String ipDiretoria, int portoDiretoria,
                                        String ultimoServidorIp, int ultimoServidorPorto) {
        System.out.println("[Cliente] Tentando reconectar...");

        try {
            String[] hp = pedirServidorPrincipal(ipDiretoria, portoDiretoria, 3000, 3);
            String novoIp = hp[0];
            int novoPorto = Integer.parseInt(hp[1]);

            if (novoIp.equals(ultimoServidorIp) && novoPorto == ultimoServidorPorto) {
                System.out.println("[Cliente] Diretoria devolveu o mesmo servidor.");

                Thread.sleep(TIMEOUT_RECONEXAO_MS);

                hp = pedirServidorPrincipal(ipDiretoria, portoDiretoria, 3000, 3);
                novoIp = hp[0];
                novoPorto = Integer.parseInt(hp[1]);

                if (novoIp.equals(ultimoServidorIp) && novoPorto == ultimoServidorPorto) {
                    System.err.println("[Cliente] ✗ Diretoria continua a devolver servidor não disponível.");
                    System.err.println("[Cliente] ✗ A terminar o cliente.");
                    System.exit(1);
                }
            }

            System.out.println("[Cliente] ✓ Novo servidor principal obtido: " + novoIp + ":" + novoPorto);

        } catch (Exception e) {
            System.err.println("[Cliente] ✗ Impossível obter servidor principal: " + e.getMessage());
            System.err.println("[Cliente] ✗ A terminar o cliente.");
            System.exit(1);
        }
    }

    private static String lerResposta(BufferedReader in) throws IOException {
        String linha;
        while ((linha = in.readLine()) != null) {
            if (linha.startsWith("NOTIF:")) {
                System.out.println("[NOTIFICAÇÃO] " + linha.substring(6));
                continue;
            }
            return linha;
        }
        return null;
    }

    private static void processarPerguntaParaResponder(String resp, Scanner sc,
                                                       PrintWriter out, BufferedReader in) throws IOException {
        String dados = resp.substring("PERGUNTA_PARA_RESPONDER:".length());
        String[] blocos = dados.split("\\|");

        String[] cab = blocos[0].split(";");
        String pid        = cab[0];
        String enunciado  = cab[1];
        String dataIni    = cab[2];
        String dataFim    = cab[3];
        String codigo     = cab[4];

        System.out.println("\n┌─ Pergunta #" + pid + " (código " + codigo + ") ─────");
        System.out.println("│ Enunciado: " + enunciado);
        System.out.println("│ Período: " + dataIni + " até " + dataFim);
        System.out.println("├─ Opções:");

        int idx = 1;
        if (idx < blocos.length && blocos[idx].startsWith("OPCOES:")) {
            int numOpcoes = Integer.parseInt(blocos[idx].substring("OPCOES:".length()));
            idx++;

            for (int i = 0; i < numOpcoes && idx < blocos.length; i++, idx++) {
                String[] oc = blocos[idx].split(";", 2);
                System.out.println("│   " + oc[0] + ") " + oc[1]);
            }
        }
        System.out.println("└─────────────────────────────────────────");

        System.out.print("\nLetra da opção: ");
        String letra = sc.nextLine().trim();
        if (!letra.isEmpty())
            letra = letra.substring(0, 1).toLowerCase();

        String wire2 = "RESPONDER;" + pid + ";" + letra;
        out.println(wire2);
        String resp2 = lerResposta(in);
        if (resp2 == null) {
            throw new IOException("Ligação fechada pelo servidor.");
        }
        System.out.println("[Cliente] " + resp2);
    }

    private static void imprimirListaPerguntas(String resp) {
        String[] partes = resp.substring(16).split("\\|");
        int count = Integer.parseInt(partes[0]);

        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║         LISTA DE PERGUNTAS (" + count + " encontrada(s))        ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        for (int i = 1; i < partes.length; i++) {
            String[] campos = partes[i].split(";");
            if (campos.length >= 7) {
                System.out.printf("┌─ Pergunta #%s ─────────────────────────────────────\n", campos[0]);
                System.out.printf("│ Enunciado: %s\n", campos[1]);
                System.out.printf("│ Período: %s até %s\n", campos[2], campos[3]);
                System.out.printf("│ Código: %s | Estado: %s\n", campos[4], campos[5]);
                System.out.printf("│ Respostas: %s\n", campos[6]);
                System.out.println("└─────────────────────────────────────────────────────\n");
            }
        }
    }

    private static void imprimirResultados(String resp) {
        String payload = resp.substring("RESULTADOS:".length());
        String[] blocos = payload.split("\\|");

        if (blocos.length == 0) {
            System.out.println("[Cliente] Resposta de resultados vazia.");
            return;
        }

        String[] infoPerg = blocos[0].split(";");
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║              RESULTADOS DA PERGUNTA #" + infoPerg[0] + "              ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝");
        System.out.println("Enunciado: " + infoPerg[1]);
        System.out.println("Período: " + infoPerg[2] + " até " + infoPerg[3]);
        System.out.println("Código: " + infoPerg[4]);
        System.out.println("Total de respostas: " + infoPerg[5] + "\n");

        int idx = 1;

        if (idx < blocos.length && blocos[idx].startsWith("OPCOES:")) {
            int numOpcoes = 0;
            try {
                numOpcoes = Integer.parseInt(blocos[idx].substring("OPCOES:".length()));
            } catch (NumberFormatException ignore) {}
            idx++;

            System.out.println("─── OPÇÕES ───");
            for (int i = 0; i < numOpcoes && idx < blocos.length; i++, idx++) {
                String[] opcao = blocos[idx].split(";", 4);
                if (opcao.length < 4) continue;
                String correta = "1".equals(opcao[2]) ? " [CORRETA]" : "";
                System.out.printf("  %s) %s%s (escolhida por %s estudante(s))\n",
                        opcao[0], opcao[1], correta, opcao[3]);
            }
            System.out.println();
        }

        if (idx < blocos.length && blocos[idx].startsWith("RESPOSTAS:")) {
            int numResp = 0;
            try {
                numResp = Integer.parseInt(blocos[idx].substring("RESPOSTAS:".length()));
            } catch (NumberFormatException ignore) {}
            idx++;

            System.out.println("─── RESPOSTAS DOS ESTUDANTES ───");
            int certas = 0;
            for (int i = 0; i < numResp && idx < blocos.length; i++, idx++) {
                String[] r = blocos[idx].split(";", 6);
                if (r.length < 6) continue;
                System.out.printf("  %s | %s (%s)\n    Resposta: %s - %s\n    Data/Hora: %s\n\n",
                        r[0], r[1], r[2], r[3], r[4], r[5]);
                if ("CERTA".equals(r[4])) certas++;
            }

            if (numResp > 0) {
                double percentagem = (certas * 100.0) / numResp;
                System.out.printf("─── ESTATÍSTICAS ───\n");
                System.out.printf("  Respostas certas: %d/%d (%.1f%%)\n", certas, numResp, percentagem);
            }
        }
    }

    private static void imprimirRespostasEstudante(String resp) {
        String payload = resp.substring("RESPOSTAS_ESTUDANTE:".length());
        String[] partes = payload.split("\\|");
        int count = Integer.parseInt(partes[0]);

        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║      PERGUNTAS RESPONDIDAS (EXPIRADAS) - " + count + " registo(s)      ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        for (int i = 1; i < partes.length; i++) {
            String[] campos = partes[i].split(";", 6);
            if (campos.length < 6) continue;

            String pid         = campos[0];
            String enunciado   = campos[1];
            String dataFim     = campos[2];
            String dataResp    = campos[3];
            String letra       = campos[4];
            String estadoResp  = campos[5];

            System.out.printf("┌─ Pergunta #%s ─────────────────────────────────────\n", pid);
            System.out.printf("│ Enunciado: %s\n", enunciado);
            System.out.printf("│ Data fim:  %s\n", dataFim);
            System.out.printf("│ Respondida: %s\n", dataResp);
            System.out.printf("│ Sua resposta: %s (%s)\n", letra, estadoResp);
            System.out.println("└─────────────────────────────────────────────────────\n");
        }
    }

    private static void exportarCSV(String resp, String wire) {
        String csvBase64 = resp.substring(14);
        byte[] csvBytes = java.util.Base64.getDecoder().decode(csvBase64);
        String csv = new String(csvBytes, java.nio.charset.StandardCharsets.UTF_8);

        String pid = wire.split(";")[1];
        String nomeFicheiro = "pergunta_" + pid + "_resultados.csv";

        try {
            FileWriter fw = new FileWriter(nomeFicheiro);
            fw.write(csv);
            fw.close();
            System.out.println("[Cliente] ✓ CSV exportado com sucesso: " + nomeFicheiro);
        } catch (IOException ioe) {
            System.err.println("[Cliente] ✗ Erro ao guardar CSV: " + ioe.getMessage());
        }
    }

    private static String[] pedirServidorPrincipal(String ipDir, int portoDir, int timeoutMs, int tentativas) throws Exception {
        DatagramSocket udp = new DatagramSocket();
        udp.setSoTimeout(timeoutMs);
        InetAddress ip = InetAddress.getByName(ipDir);
        byte[] req = "PEDIDO_CLIENTE_SERVIDOR".getBytes();

        for (int i = 1; i <= tentativas; i++) {
            try {
                udp.send(new DatagramPacket(req, req.length, ip, portoDir));
                byte[] buf = new byte[1024];
                DatagramPacket respPkt = new DatagramPacket(buf, buf.length);
                udp.receive(respPkt);
                String resposta = new String(respPkt.getData(), 0, respPkt.getLength()).trim();
                if (resposta.startsWith("ERRO")) throw new IOException(resposta);

                String[] partes = resposta.split(":");
                if (partes.length < 2) {
                    throw new IOException("Resposta mal formatada da diretoria: " + resposta);
                }

                String[] hp = new String[] { partes[0], partes[1] };
                udp.close();
                return hp;
            } catch (SocketTimeoutException te) {
                System.err.println("[Cliente] Sem resposta da diretoria (tentativa " + i + "/" + tentativas + ").");
            }
        }
        udp.close();
        throw new SocketTimeoutException("Diretoria não respondeu após " + tentativas + " tentativas.");
    }

    private static void fecharQuietamente(Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignore) {}
        }
    }

    private static void mostrarMenu() {
        System.out.println("\n=== MENU PRINCIPAL ===");
        System.out.println("  1) Login docente");
        System.out.println("  2) Login estudante");
        System.out.println("  3) Criar pergunta");
        System.out.println("  4) Adicionar opção");
        System.out.println("  5) Responder");
        System.out.println("  6) Registar docente");
        System.out.println("  7) Registar estudante");
        System.out.println("  8) Listar perguntas");
        System.out.println("  9) Editar pergunta");
        System.out.println(" 10) Eliminar pergunta");
        System.out.println(" 11) Ver resultados de pergunta expirada");
        System.out.println(" 12) Exportar resultados para CSV");
        System.out.println(" 13) Editar dados pessoais docente");
        System.out.println(" 14) Logout");
        System.out.println(" 15) Editar dados pessoais estudante");
        System.out.println(" 16) Ver perguntas respondidas (estudante)");
        System.out.println("\n  0) Sair");
    }
}