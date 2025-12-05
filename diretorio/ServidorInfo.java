package diretorio;

import java.net.InetAddress;
import java.time.LocalDateTime;

public class ServidorInfo {
    private final InetAddress ip;
    private final int porto;
    private final int portoSync;
    private LocalDateTime ultimaAtualizacao;

    public ServidorInfo(InetAddress ip, int porto) { this(ip, porto, -1); }

    public ServidorInfo(InetAddress ip, int porto, int portoSync) {
        this.ip = ip;
        this.porto = porto;
        this.portoSync = portoSync;
        this.ultimaAtualizacao = LocalDateTime.now();
    }

    public InetAddress getIp() { return ip; }
    public int getPorto() { return porto; }
    public int getPortoSync() { return portoSync; }
    public LocalDateTime getUltimaAtualizacao() { return ultimaAtualizacao; }
    public void atualizarHeartbeat() { this.ultimaAtualizacao = LocalDateTime.now(); }

    @Override
    public String toString() {
        return ip.getHostAddress()
                + ":" + porto
                + " (sync=" + portoSync + ", último heartbeat: " + ultimaAtualizacao + ")";
    }
}