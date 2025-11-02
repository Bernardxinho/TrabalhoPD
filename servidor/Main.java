package servidor;

import servidor.db.DatabaseManager;

public class Main {
    public static void main(String[] args) {
        String dbPath = "servidor/teste.db";

        DatabaseManager db = new DatabaseManager(dbPath);
        db.connect();          
        db.createTestTable();  
        db.close();            

        System.out.println("Servidor iniciado com base de dados!");
    }
}
