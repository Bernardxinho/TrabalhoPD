package diretorio;

import java.net.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class Main {
    private static final int PORTO_DIRETORIA = 4000;
    private static final List<ServidorInfo> servidoresAtivos =
            Collections.synchronizedList(new ArrayList<>());

    private static final boolean VERBOSE_HB = true; // true  -> mostra cada heartbeat (~5s), false -> mostra apenas resumo por minuto
    private static final DateTimeFormatter FMT_HHMMSS =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static volatile long hbCount = 0;

    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket(PORTO_DIRETORIA)) {
            System.out.println("[Diretoria] Servidor de diretoria a correr no porto " + PORTO_DIRETORIA);

            new Thread(Main::verificarInatividade, "PD-Check-Inativos").start();

            if (!VERBOSE_HB) {
                new Thread(Main::resumoPeriodico, "PD-Resumo-60s").start();
            }

            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String mensagem = new String(packet.getData(), 0, packet.getLength());
                InetAddress ip = packet.getAddress();
                int portoRemetente = packet.getPort();

                processarMensagem(socket, mensagem, ip, portoRemetente);
            }

        } catch (Exception e) {
            System.err.println("[Diretoria] Erro: " + e.getMessage());
        }
    }

    private static void processarMensagem(DatagramSocket socket, String mensagem, InetAddress ip, int porto) {
        int portoTCP = -1;
       try {
            if (mensagem.startsWith("REGISTO:")) {
                String[] p = mensagem.split(":");
                if (p.length >= 2) portoTCP = Integer.parseInt(p[1]);          // REGISTO:<portoClientes>[:portoSync]
            } else if (mensagem.startsWith("HEARTBEAT:")) {
                String[] p = mensagem.split(":");
                if (p.length >= 3) portoTCP = Integer.parseInt(p[2]);          // HEARTBEAT:<versao>:<portoClientes>:<portoSync>
            }
        } catch (NumberFormatException e) {
            System.err.println("[Diretoria] Erro a ler porto: " + mensagem);
        } catch (Exception ignored) {}

        final int portoChave = (portoTCP > -1) ? portoTCP : porto;

        Optional<ServidorInfo> existente = servidoresAtivos.stream()
                .filter(s -> s.getIp().equals(ip) && s.getPorto() == portoChave)
                .findFirst();

        try {
            if (mensagem.equals("PEDIDO_REGISTO_SERVIDOR") || mensagem.startsWith("REGISTO:")) {
                if (existente.isEmpty()) {
                    ServidorInfo novo = new ServidorInfo(ip, portoChave);
                    servidoresAtivos.add(novo);
                    System.out.println("[Diretoria] Novo servidor registado: " + novo.getIp().getHostAddress() + ":" + novo.getPorto()
                            + " (último heartbeat: " + novo.getUltimaAtualizacao().format(FMT_HHMMSS) + ")");
                    mostrarServidores();
                } else {
                    existente.get().atualizarHeartbeat();
                    if (VERBOSE_HB) {
                        System.out.println("[Diretoria] Servidor já registado, heartbeat atualizado: "
                                + ip.getHostAddress() + ":" + portoChave
                                + " (lastSeen=" + existente.get().getUltimaAtualizacao().format(FMT_HHMMSS) + ")");
                    }
                }
                enviar(socket, ip, porto, "Registo recebido com sucesso!");
            }

            else if (mensagem.equals("HEARTBEAT") || mensagem.startsWith("HEARTBEAT:")) {
                existente.ifPresentOrElse(s -> {
                    s.atualizarHeartbeat();
                    hbCount++;

                    if (VERBOSE_HB) {
                       /*  System.out.println("[Diretoria] Heartbeat recebido de "
                                + ip.getHostAddress() + ":" + portoChave
                                + " (lastSeen=" + s.getUltimaAtualizacao().format(FMT_HHMMSS) + ")"); */
                    }
                }, () -> {
                    if (VERBOSE_HB) {
                        System.out.println("[Diretoria] HEARTBEAT de servidor NÃO REGISTADO: "
                                + ip.getHostAddress() + ":" + portoChave);
                    }
                });

                enviar(socket, ip, porto, "ACK_HEARTBEAT");
            }

            else if (mensagem.equals("PEDIDO_CLIENTE_SERVIDOR")) {
                if (servidoresAtivos.isEmpty()) {
                    enviar(socket, ip, porto, "ERRO: Nenhum servidor ativo!");
                } else {
                    ServidorInfo principal = servidoresAtivos.get(0);
                    String respostaCliente = principal.getIp().getHostAddress() + ":" + principal.getPorto();
                    enviar(socket, ip, porto, respostaCliente);
                    System.out.println("[Diretoria] Enviou ao cliente o servidor principal: " + respostaCliente);
                }
            }

        } catch (Exception e) {
            System.err.println("[Diretoria] Erro ao processar mensagem: " + e.getMessage());
        }
    }

    private static void enviar(DatagramSocket socket, InetAddress ip, int porto, String msg) throws Exception {
        byte[] respostaBytes = msg.getBytes();
        DatagramPacket resposta = new DatagramPacket(respostaBytes, respostaBytes.length, ip, porto);
        socket.send(resposta);
    }

    private static void mostrarServidores() {
        System.out.println("\n[Diretoria] Servidores ativos (" + servidoresAtivos.size() + "):");
        synchronized (servidoresAtivos) {
            for (int i = 0; i < servidoresAtivos.size(); i++) {
                ServidorInfo s = servidoresAtivos.get(i);
                String papel = (i == 0 ? " (PRINCIPAL)" : "");
                String hora = s.getUltimaAtualizacao().format(FMT_HHMMSS);
                System.out.println("   - " + s.getIp().getHostAddress() + ":" + s.getPorto()
                        + papel + " (último heartbeat: " + hora + ")");
            }
        }
        System.out.println("---------------------------------------");
    }

    private static void verificarInatividade() {
        while (true) {
            try {
                Thread.sleep(5000); // verifica a cada 5s
                boolean mudou = false;

                synchronized (servidoresAtivos) {
                    Iterator<ServidorInfo> it = servidoresAtivos.iterator();
                    while (it.hasNext()) {
                        ServidorInfo s = it.next();
                        Duration tempo = Duration.between(s.getUltimaAtualizacao(), LocalDateTime.now());
                        if (tempo.getSeconds() > 17) {
                            System.out.println("[Diretoria] Servidor removido por inatividade: "
                                    + s.getIp().getHostAddress() + ":" + s.getPorto()
                                    + " (lastSeen=" + s.getUltimaAtualizacao().format(FMT_HHMMSS) + ")");
                            it.remove();
                            mudou = true;
                        }
                    }
                }

                if (mudou) {
                    if (!servidoresAtivos.isEmpty()) {
                        ServidorInfo novoPrincipal = servidoresAtivos.get(0);
                        System.out.println("[Diretoria] Novo servidor principal: "
                                + novoPrincipal.getIp().getHostAddress() + ":" + novoPrincipal.getPorto());
                    }
                    mostrarServidores();
                }

            } catch (Exception e) {
                System.err.println("[Diretoria] Erro ao verificar inatividade: " + e.getMessage());
            }
        }
    }

    // VERBOSE_HB=false
    private static void resumoPeriodico() {
        while (true) {
            try {
                Thread.sleep(60_000); //1 minuto
                ServidorInfo principal;
                int ativos;

                synchronized (servidoresAtivos) {
                    ativos = servidoresAtivos.size();
                    principal = ativos == 0 ? null : servidoresAtivos.get(0);
                }

                String linha = (principal == null)
                        ? "[Diretoria] Resumo: 0 servidores ativos | HB/min=" + hbCount
                        : "[Diretoria] Resumo: " + ativos + " ativos | Principal: "
                        + principal.getIp().getHostAddress() + ":" + principal.getPorto()
                        + " | HB/min=" + hbCount;

                System.out.println(linha);
                hbCount = 0;
            } catch (InterruptedException ignore) { }
            catch (Exception e) {
                System.err.println("[Diretoria] Erro no resumo: " + e.getMessage());
            }
        }
    }
}
