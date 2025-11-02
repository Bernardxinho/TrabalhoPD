package servidor;

import servidor.db.DatabaseManager;

public class Main {
    public static void main(String[] args) {
        System.out.println("=== Servidor de Perguntas - Iniciando ===");

        String dbPath = "servidor/sistema.db";

        DatabaseManager db = new DatabaseManager(dbPath);
        db.connect();
        db.createTables();

        System.out.println("\n[INFO] Versão atual da BD: " + db.getVersao());
        System.out.println("[INFO] Código de registo docente: DOCENTE2025");
        System.out.println("[INFO] Base de dados pronta!");

        // db.close();  // Comentado para manter conexão ativa

        System.out.println("\n=== Servidor pronto para aceitar conexões ===");
    }
}