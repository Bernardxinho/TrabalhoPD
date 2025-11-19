package servidor.db;

import java.util.ArrayList;
import java.util.List;

public class PerguntaDetalhes {
    public int id;
    public String enunciado;
    public String dataInicio;
    public String dataFim;
    public String codigoAcesso;
    public int docenteId;
    public String dataCriacao;
    public String estado; 
    public int numRespostas;

    public List<OpcaoDetalhes> opcoes = new ArrayList<>();
    public List<RespostaDetalhes> respostas = new ArrayList<>();

    public static class OpcaoDetalhes {
        public int id;
        public String letra;
        public String texto;
        public boolean isCorreta;
        public int numRespostas; 

        public OpcaoDetalhes(int id, String letra, String texto, boolean isCorreta) {
            this.id = id;
            this.letra = letra;
            this.texto = texto;
            this.isCorreta = isCorreta;
            this.numRespostas = 0;
        }
    }

    public static class RespostaDetalhes {
        public int estudanteId;
        public int estudanteNumero;
        public String estudanteNome;
        public String estudanteEmail;
        public String opcaoLetra;
        public String dataHora;
        public boolean estaCorreta;

        public RespostaDetalhes(int estudanteId, int estudanteNumero, String estudanteNome,
                                String estudanteEmail, String opcaoLetra, String dataHora, boolean estaCorreta) {
            this.estudanteId = estudanteId;
            this.estudanteNumero = estudanteNumero;
            this.estudanteNome = estudanteNome;
            this.estudanteEmail = estudanteEmail;
            this.opcaoLetra = opcaoLetra;
            this.dataHora = dataHora;
            this.estaCorreta = estaCorreta;
        }
    }

    public PerguntaDetalhes() {
    }
}