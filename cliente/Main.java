package cliente;

import java.net.*;
import java.io.*;

public class Main {
    public static void main(String[] args) {
        try {
            String ipDiretoria = "127.0.0.1";
            int portoDiretoria = 4000;
            DatagramSocket socketUDP = new DatagramSocket();

            String pedido = "PEDIDO_CLIENTE_SERVIDOR";
            byte[] dados = pedido.getBytes();
            InetAddress ip = InetAddress.getByName(ipDiretoria);
            DatagramPacket packet = new DatagramPacket(dados, dados.length, ip, portoDiretoria);
            socketUDP.send(packet);
            System.out.println("[Cliente] Pedido enviado à diretoria...");

            byte[] buffer = new byte[1024];
            DatagramPacket respostaPacket = new DatagramPacket(buffer, buffer.length);
            socketUDP.receive(respostaPacket);
            String resposta = new String(respostaPacket.getData(), 0, respostaPacket.getLength());
            socketUDP.close();

            if (resposta.startsWith("ERRO")) {
                System.out.println("[Cliente] Erro: " + resposta);
                return;
            }

            String[] partes = resposta.split(":");
            String ipServidor = partes[0];
            int portoServidor = Integer.parseInt(partes[1]);
            System.out.println("[Cliente] Servidor principal: " + ipServidor + ":" + portoServidor);

            Socket socketTCP = new Socket(ipServidor, portoServidor);
            System.out.println("[Cliente] Ligado ao servidor via TCP!");

            BufferedReader input = new BufferedReader(new InputStreamReader(socketTCP.getInputStream()));
            PrintWriter output = new PrintWriter(socketTCP.getOutputStream(), true);

            output.println("Olá servidor! Sou o cliente 😎");
            String respostaServidor = input.readLine();
            System.out.println("[Cliente] Servidor respondeu: " + respostaServidor);

            socketTCP.close();
        } catch (Exception e) {
            System.err.println("[Cliente] Erro: " + e.getMessage());
        }
    }
}
