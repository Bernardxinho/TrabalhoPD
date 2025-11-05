package diretorio;

import java.net.InetAddress;
import java.time.LocalDateTime;

public class ServidorInfo {
    private final InetAddress ip;
    private final int porto;
    private LocalDateTime ultimaAtualizacao;

    public ServidorInfo(InetAddress ip, int porto) {
        this.ip = ip;
        this.porto = porto;
        this.ultimaAtualizacao = LocalDateTime.now();
    }

    public InetAddress getIp() { return ip; }
    public int getPorto() { return porto; }
    public LocalDateTime getUltimaAtualizacao() { return ultimaAtualizacao; }

    public void atualizarHeartbeat() {
        this.ultimaAtualizacao = LocalDateTime.now();
    }

    @Override
    public String toString() {
        return ip.getHostAddress() + ":" + porto + " (último heartbeat: " + ultimaAtualizacao + ")";
    }
}
