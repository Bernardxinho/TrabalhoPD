package diretorio;

import java.net.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

public class Main {
    private static final int PORTO_DIRETORIA = 4000;
    private static final List<ServidorInfo> servidoresAtivos = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) {
        try (DatagramSocket socket = new DatagramSocket(PORTO_DIRETORIA)) {
            System.out.println("[Diretoria] Servidor de diretoria a correr no porto " + PORTO_DIRETORIA);

            new Thread(() -> verificarInatividade()).start();

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
        Optional<ServidorInfo> existente = servidoresAtivos.stream()
                .filter(s -> s.getIp().equals(ip) && s.getPorto() == porto)
                .findFirst();

        try {
            if (mensagem.equals("PEDIDO_REGISTO_SERVIDOR")) {
                if (existente.isEmpty()) {
                    ServidorInfo novo = new ServidorInfo(ip, porto);
                    servidoresAtivos.add(novo);
                    System.out.println("[Diretoria] Novo servidor registado: " + novo);
                } else {
                    existente.get().atualizarHeartbeat();
                    System.out.println("[Diretoria] Servidor já registado, heartbeat atualizado.");
                }
                enviar(socket, ip, porto, "Registo recebido com sucesso!");
            }

            else if (mensagem.equals("HEARTBEAT")) {
                existente.ifPresent(s -> {
                    s.atualizarHeartbeat();
                    System.out.println("[Diretoria] Heartbeat recebido de " + ip.getHostAddress() + ":" + porto);
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

        mostrarServidores();
    }


    private static void enviar(DatagramSocket socket, InetAddress ip, int porto, String msg) throws Exception {
        byte[] respostaBytes = msg.getBytes();
        DatagramPacket resposta = new DatagramPacket(respostaBytes, respostaBytes.length, ip, porto);
        socket.send(resposta);
    }

    private static void mostrarServidores() {
        System.out.println("\n[Diretoria] Servidores ativos (" + servidoresAtivos.size() + "):");
        synchronized (servidoresAtivos) {
            for (ServidorInfo s : servidoresAtivos)
                System.out.println("   - " + s);
        }
        System.out.println("---------------------------------------");
    }

    private static void verificarInatividade() {
        while (true) {
            try {
                Thread.sleep(5000); 
                synchronized (servidoresAtivos) {
                    servidoresAtivos.removeIf(s -> {
                        Duration tempo = Duration.between(s.getUltimaAtualizacao(), LocalDateTime.now());
                        if (tempo.getSeconds() > 17) {
                            System.out.println("[Diretoria] ❌ Servidor removido por inatividade: " + s);
                            return true;
                        }
                        return false;
                    });
                }
            } catch (Exception e) {
                System.err.println("[Diretoria] Erro ao verificar inatividade: " + e.getMessage());
            }
        }
    }
}
