# rag-mcp-project

Chatbot RAG (Retrieval-Augmented Generation) sobre documentos próprios
(PDF/TXT), construído em Java + Spring Boot + LangChain4j, com PGVector
como vector database, Ollama como LLM/embeddings local, e integração com
MCP (Model Context Protocol) — tanto como **servidor MCP** (expondo o RAG
para clientes externos) quanto como **cliente MCP** (consumindo
ferramentas externas como fallback).

Este projeto é **guiado por specs**: toda a funcionalidade está descrita
em `/specs` antes de qualquer implementação. Veja `specs/00-overview.md`
para a visão geral e os links para cada módulo.

## Specs
| Spec | Módulo |
|------|--------|
| [00-overview.md](specs/00-overview.md) | Visão geral do projeto |
| [01-document-ingestion.md](specs/01-document-ingestion.md) | Ingestão de documentos (PDF/TXT → PGVector) |
| [02-rag-query.md](specs/02-rag-query.md) | Consulta RAG (pergunta → resposta com fontes) |
| [03-mcp-server.md](specs/03-mcp-server.md) | Servidor MCP (expõe o RAG como ferramentas) |
| [04-mcp-client.md](specs/04-mcp-client.md) | Cliente MCP (fallback com ferramentas externas) |
| [05-api-contracts.md](specs/05-api-contracts.md) | Contratos REST |
| [06-whatsapp-integration.md](specs/06-whatsapp-integration.md) | Webhook WhatsApp (Twilio Sandbox) |

## Pré-requisitos
- Java 21
- Maven 3.9+
- Docker + Docker Compose

## Subindo a infraestrutura local
```bash
docker compose up -d
# aguarde o healthcheck do Postgres ficar "healthy"

# baixe os modelos no Ollama (uma vez só):
docker exec -it rag-mcp-ollama ollama pull llama3.1
docker exec -it rag-mcp-ollama ollama pull nomic-embed-text
```

## Rodando a aplicação (modo REST)
```bash
mvn spring-boot:run
```
A API sobe em `http://localhost:8080`. Veja os contratos em
`specs/05-api-contracts.md`.

## Testando um chatbot no WhatsApp (gratuito)
O projeto expõe `POST /webhook/whatsapp/twilio`, compatível com o
**Twilio WhatsApp Sandbox** (spec 06). Para testar cenários reais no
WhatsApp sem custo:

1. Rode a aplicação localmente (`mvn spring-boot:run`).
2. Crie uma conta gratuita em https://www.twilio.com/try-twilio e ative o
   **WhatsApp Sandbox** (Messaging → Try it out → Send a WhatsApp
   message). Você receberá um código tipo `join palavra-exemplo`.
3. Exponha a porta 8080 publicamente com um túnel gratuito, ex:
   ```bash
   ngrok http 8080
   ```
   Copie a URL HTTPS gerada (ex: `https://abcd1234.ngrok-free.app`).
4. No console do Twilio Sandbox, em "WHEN A MESSAGE COMES IN", configure:
   ```
   https://abcd1234.ngrok-free.app/webhook/whatsapp/twilio
   ```
   (método `HTTP POST`).
5. Pelo seu WhatsApp pessoal, envie `join palavra-exemplo` para o número
   do sandbox exibido no console do Twilio.
6. Envie perguntas normalmente — cada número de telefone vira uma sessão
   independente do RAG (histórico preservado por contato).

Isso é 100% gratuito para testes: Twilio Sandbox e ngrok não cobram nesse
uso. Não é adequado para produção (o sandbox expira periodicamente e
exige reenviar o `join`); para produção, migre para o **Meta WhatsApp
Cloud API** (também tem camada gratuita) ou para uma conta paga do
Twilio. Detalhes do contrato do webhook em `specs/06-whatsapp-integration.md`.

## Rodando como servidor MCP (stdio)
Para expor o RAG como ferramentas MCP (para uso em clientes como Claude
Desktop), ative o modo stdio conforme configurado em `application.yml`
(`mcp.server.stdio-enabled`) — veja `specs/03-mcp-server.md` para os
detalhes das ferramentas expostas (`ask_question`, `search_documents`,
`ingest_document`).

## Configurando ferramentas MCP externas (fallback)
Edite `mcp-servers-config.json` na raiz do projeto para declarar
servidores MCP externos que o sistema deve consumir quando o contexto
local for insuficiente (spec 04). Exemplo com o servidor de filesystem
oficial (requer Node.js):
```json
{
  "servers": [
    {
      "name": "filesystem",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/caminho/para/docs"]
    }
  ]
}
```

## Estrutura do código
```
src/main/java/com/example/ragmcp/
  config/     -> beans do LangChain4j (Ollama, PGVector)
  ingestion/  -> spec 01
  rag/        -> spec 02
  mcpserver/  -> spec 03
  mcpclient/  -> spec 04
  api/        -> spec 05
  whatsapp/   -> spec 06
```

## Desenvolvimento (spec-driven)
Nenhuma mudança de comportamento deve ser feita sem antes atualizar a
spec correspondente em `/specs`. Cada critério de aceite de cada spec
deve ter um teste automatizado associado.
