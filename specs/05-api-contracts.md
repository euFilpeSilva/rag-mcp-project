# Spec 05 — Contratos de API REST (Internos/Testes)

## Objetivo
Expor uma API REST simples para testes locais e integração futura com um
frontend, cobrindo ingestão e consulta, sem envolver diretamente o
protocolo MCP (que é consumido por clientes externos, spec 03).

## Requisitos Funcionais
- RF01: `POST /api/documents` — recebe multipart/form-data com um arquivo,
  ingere e retorna `IngestionResult`.
- RF02: `POST /api/documents/batch` — recebe um caminho de diretório
  (JSON `{ "directoryPath": "..." }`) acessível pelo servidor, ingere todos
  os arquivos suportados e retorna lista de `IngestionResult`.
- RF03: `POST /api/chat` — recebe `{ "sessionId": "...", "question": "..." }`
  e retorna `RagAnswer`.
- RF04: `GET /api/health` — retorna status da aplicação e conectividade com
  Postgres/Ollama (`{ "status": "UP", "postgres": "UP", "ollama": "UP" }`).

## Requisitos Não-Funcionais
- RNF01: Todas as respostas devem ser JSON (`application/json`).
- RNF02: Erros de validação (ex: campo obrigatório ausente) devem retornar
  HTTP 400 com corpo `{ "error": "mensagem descritiva" }`.
- RNF03: Erros internos inesperados devem retornar HTTP 500 sem vazar
  stack trace no corpo da resposta (apenas em log).

## Contratos Detalhados

### POST /api/chat
**Request**
```json
{ "sessionId": "abc-123", "question": "Qual o prazo de garantia do produto X?" }
```
**Response 200**
```json
{
  "answer": "O prazo de garantia é de 12 meses...",
  "sources": [
    { "fileName": "manual.pdf", "pageNumber": 3, "similarityScore": 0.87 }
  ],
  "insufficientContext": false
}
```

### GET /api/health
**Response 200**
```json
{ "status": "UP", "postgres": "UP", "ollama": "UP" }
```

## Critérios de Aceite
- CA01: `POST /api/chat` sem o campo `question` deve retornar HTTP 400.
- CA02: `POST /api/documents` com um PDF válido deve retornar HTTP 200 e
  `chunksCreated > 0`.
- CA03: `GET /api/health` com Postgres fora do ar deve retornar
  `"postgres": "DOWN"` e HTTP 200 (o endpoint de health não falha, reporta
  o problema).
- CA04: Todas as respostas de erro devem seguir o formato
  `{ "error": "..." }`.

## Testes Esperados
- Testes de integração com `MockMvc` ou `RestAssured` (dependendo do
  framework web escolhido) cobrindo CA01–CA04.
