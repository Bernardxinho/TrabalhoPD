# Projeto PD 2025/26 - Grupo 15

Sistema distribuído para gestão de perguntas de escolha múltipla.

## Estrutura
- `/diretorio` → Serviço de diretoria (UDP)
- `/servidor` → Servidor principal/backup (UDP + TCP + Multicast)
- `/cliente` → Interface para docente/estudante (TCP + UDP)
