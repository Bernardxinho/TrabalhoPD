// src/servidor/ReplicationSender.java
package servidor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class ReplicationSender {
    private final DatagramSocket socket;
    private final InetAddress grupoMulticast;
    private final int multicastPort;
    private final int portoTCPClientes;
    private final int portoTCPSync;

    public ReplicationSender(DatagramSocket socket,
                             InetAddress grupoMulticast,
                             int multicastPort,
                             int portoTCPClientes,
                             int portoTCPSync) {
        this.socket = socket;
        this.grupoMulticast = grupoMulticast;
        this.multicastPort = multicastPort;
        this.portoTCPClientes = portoTCPClientes;
        this.portoTCPSync = portoTCPSync;
    }

    public void sendUpdate(int versao, String querySql) {
        try {
            String msg = "HEARTBEAT_UPDATE:" + versao + ":"
                       + portoTCPClientes + ":" + portoTCPSync
                       + ":QUERY:" + querySql;
            byte[] bytes = msg.getBytes();
            DatagramPacket pkt =
                new DatagramPacket(bytes, bytes.length, grupoMulticast, multicastPort);
            socket.send(pkt);
            System.out.println("[Replicator] UPDATE enviado (v" + versao + ")");
            System.out.println("            SQL: " + querySql);
        } catch (Exception e) {
            System.err.println("[Replicator] Falha ao enviar UPDATE: " + e.getMessage());
        }
    }
}
