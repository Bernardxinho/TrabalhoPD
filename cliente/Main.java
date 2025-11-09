package cliente;

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Main {
    private static final String DEF_IP_DIR = "127.0.0.1";
    private static final int DEF_PORT_DIR = 4000;

    public static void main(String[] args) {
        String ipDiretoria = (args.length >= 1) ? args[0] : DEF_IP_DIR;
        int portoDiretoria = (args.length >= 2) ? Integer.parseInt(args[1]) : DEF_PORT_DIR;

        Scanner sc = new Scanner(System.in);

        while (true) {
            try {
                // 1) Perguntar à diretoria quem é o principal (UDP com timeout e retry)
                String[] hp = pedirServidorPrincipal(ipDiretoria, portoDiretoria, 3000, 3);
                String ipServidor = hp[0];
                int portoServidor = Integer.parseInt(hp[1]);
                System.out.printf("[Cliente] Servidor principal: %s:%d%n", ipServidor, portoServidor);

                // 2) Abrir TCP (com timeout) e entrar no loop interativo
                try (Socket socketTCP = new Socket()) {
                    socketTCP.connect(new InetSocketAddress(ipServidor, portoServidor), 4000);
                    socketTCP.setSoTimeout(30000); // 30s para leitura
                    System.out.println("[Cliente] Ligado ao servidor via TCP!");

                    try (BufferedReader in = new BufferedReader(new InputStreamReader(socketTCP.getInputStream()));
                         PrintWriter out = new PrintWriter(socketTCP.getOutputStream(), true)) {

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
                                case "1": { // Login docente
                                    System.out.print("Email docente: "); String email = sc.nextLine().trim();
                                    System.out.print("Password: ");       String pass  = sc.nextLine().trim();
                                    wire = "LOGIN_DOCENTE;" + email + ";" + pass;
                                    break;
                                }
                                case "2": { // Login estudante
                                    System.out.print("Email estudante: "); String email = sc.nextLine().trim();
                                    System.out.print("Password: ");        String pass  = sc.nextLine().trim();
                                    wire = "LOGIN_ESTUDANTE;" + email + ";" + pass;
                                    break;
                                }
                                case "3": { // Criar pergunta
                                    System.out.print("Docente ID: ");                 String docId = sc.nextLine().trim();
                                    System.out.print("Enunciado: ");                  String enun  = sc.nextLine().trim();
                                    System.out.print("Início (AAAA-MM-DD HH:mm): ");  String ini   = sc.nextLine().trim();
                                    System.out.print("Fim    (AAAA-MM-DD HH:mm): ");  String fim   = sc.nextLine().trim();
                                    wire = "CRIAR_PERGUNTA;" + docId + ";" + enun + ";" + ini + ";" + fim;
                                    break;
                                }
                                case "4": { // Adicionar opção
                                    System.out.print("Pergunta ID: ");  String pid   = sc.nextLine().trim();
                                    System.out.print("Letra (a/b/c): "); String letra = sc.nextLine().trim();
                                    if (!letra.isEmpty()) letra = letra.substring(0,1).toLowerCase();
                                    System.out.print("Texto da opção: "); String txt  = sc.nextLine().trim();
                                    System.out.print("É correta? (1/0): "); String ok = sc.nextLine().trim();
                                    ok = "1".equals(ok) ? "1" : "0";
                                    wire = "ADICIONAR_OPCAO;" + pid + ";" + letra + ";" + txt + ";" + ok;
                                    break;
                                }
                                case "5": { // Responder
                                    System.out.print("Estudante ID: ");  String estId = sc.nextLine().trim();
                                    System.out.print("Pergunta ID: ");    String pid   = sc.nextLine().trim();
                                    System.out.print("Letra (a/b/c): ");  String letra = sc.nextLine().trim();
                                    if (!letra.isEmpty()) letra = letra.substring(0,1).toLowerCase();
                                    wire = "RESPONDER;" + estId + ";" + pid + ";" + letra;
                                    break;
                                }case "6": { // Registar docente
                                    System.out.print("Nome: ");        String nome = sc.nextLine().trim();
                                    System.out.print("Email: ");       String email = sc.nextLine().trim();
                                    System.out.print("Password: ");    String pass = sc.nextLine().trim();
                                    System.out.print("Código docente: "); String cod = sc.nextLine().trim(); // por defeito: DOCENTE2025
                                    wire = "REGISTAR_DOCENTE;" + nome + ";" + email + ";" + pass + ";" + cod;
                                    break;
                                }
                                case "7": { // Registar estudante
                                    System.out.print("Número: ");      String num  = sc.nextLine().trim();
                                    System.out.print("Nome: ");        String nome = sc.nextLine().trim();
                                    System.out.print("Email: ");       String email= sc.nextLine().trim();
                                    System.out.print("Password: ");    String pass = sc.nextLine().trim();
                                    wire = "REGISTAR_ESTUDANTE;" + num + ";" + nome + ";" + email + ";" + pass;
                                    break;
                                }

                                default:
                                    System.out.println("[Cliente] Opção inválida.");
                                    continue;
                            }

                            // Envia e lê 1 linha de resposta
                            if (wire != null && !wire.isEmpty()) {
                                out.println(wire);
                                String resp = lerLinhaComTimeout(in);
                                if (resp == null) {
                                    System.out.println("[Cliente] Ligação fechada pelo servidor.");
                                    break; // volta a perguntar à diretoria e reconectar
                                }
                                System.out.println("[Cliente] " + resp);
                            }
                        }
                    }
                }

            } catch (SocketTimeoutException te) {
                System.err.println("[Cliente] Timeout. Vou voltar a perguntar à diretoria...");
            } catch (IOException ioe) {
                System.err.println("[Cliente] Falha de ligação: " + ioe.getMessage());
                System.err.println("[Cliente] Vou tentar voltar a ligar ao principal...");
            } catch (Exception e) {
                System.err.println("[Cliente] Erro: " + e.getMessage());
                e.printStackTrace();
                break;
            }

            // Pequeno backoff antes de tentar reconectar
            try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
        }
    }

    // ---- Helpers ----

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
                if (partes.length != 2) throw new IOException("Resposta mal formatada da diretoria: " + resposta);
                udp.close();
                return partes; // [ip, porto]
            } catch (SocketTimeoutException te) {
                System.err.println("[Cliente] Sem resposta da diretoria (tentativa " + i + "/" + tentativas + ").");
            }
        }
        udp.close();
        throw new SocketTimeoutException("Diretoria não respondeu.");
    }

    private static String lerLinhaComTimeout(BufferedReader in) throws IOException {
        // readLine() já respeita o SO_TIMEOUT do socket (definido em setSoTimeout)
        return in.readLine();
    }

    private static void mostrarMenu() {
        System.out.println("\nComandos simples:");
        System.out.println("  1) Login docente");
        System.out.println("  2) Login estudante");
        System.out.println("  3) Criar pergunta");
        System.out.println("  4) Adicionar opção");
        System.out.println("  5) Responder");
        System.out.println("  6) Registar docente");
        System.out.println("  7) Registar estudante");
        System.out.println("  0) Sair");
    }
}
