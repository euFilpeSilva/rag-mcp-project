# Spec 00 — Visão Geral do Projeto

## Nome
`rag-mcp-project` — Chatbot RAG com MCP (Java + LangChain4j)

## Objetivo
Construir um chatbot capaz de responder perguntas sobre documentos próprios
(PDFs e textos), usando RAG (Retrieval-Augmented Generation), e expor essa
capacidade via um servidor MCP (Model Context Protocol). O sistema também
atua como cliente MCP, consumindo ferramentas externas para enriquecer
respostas quando o contexto local não é suficiente.

## Stack Tecnológica
| Camada              | Tecnologia                                  |
|---------------------|----------------------------------------------|
| Linguagem            | Java 21                                      |
| Build                | Maven                                        |
| Framework RAG/LLM    | LangChain4j                                  |
| Vector Database      | PostgreSQL + pgvector                        |
| LLM / Embeddings     | Ollama (local) — `llama3.1` e `nomic-embed-text` |
| Protocolo de agentes | MCP (Model Context Protocol)                 |
| Testes               | JUnit 5 + Testcontainers                     |
| Infra local          | Docker Compose (Postgres+pgvector, Ollama)   |

## Módulos do Sistema
1. **Ingestion Module** — carrega documentos (PDF/TXT), faz split em chunks,
   gera embeddings e persiste no PGVector. Spec: `01-document-ingestion.md`
2. **RAG Query Module** — recebe pergunta em linguagem natural, recupera
   chunks relevantes, monta prompt aumentado e chama o LLM. Spec:
   `02-rag-query.md`
3. **MCP Server Module** — expõe o RAG como ferramentas MCP
   (`search_documents`, `ask_question`, `ingest_document`) para clientes MCP
   externos (ex: Claude Desktop, IDEs). Spec: `03-mcp-server.md`
4. **MCP Client Module** — o próprio sistema atua como cliente MCP,
   consumindo ferramentas externas (ex: filesystem, web search) quando o
   contexto local é insuficiente. Spec: `04-mcp-client.md`
5. **API Contracts** — contratos REST internos usados para testes e
   integração com um frontend futuro. Spec: `05-api-contracts.md`

## Princípios de Desenvolvimento (Spec-Driven)
- Nenhum código é escrito sem uma spec correspondente aprovada.
- Cada spec define: **Requisitos Funcionais**, **Requisitos Não-Funcionais**,
  **Contratos** (interfaces/APIs) e **Critérios de Aceite** testáveis.
- Cada critério de aceite deve ser rastreável a pelo menos um teste
  automatizado (unitário ou de integração).
- Mudanças de comportamento exigem atualização da spec **antes** do código.

## Fora de Escopo (v1)
- Autenticação/autorização de usuários.
- Interface gráfica (frontend web). Apenas API REST + MCP.
- Suporte multi-tenant.
- Suporte a outros formatos além de PDF e TXT.

## Fluxo de Alto Nível
```
[PDF/TXT] --> Ingestion Module --> pgvector (embeddings)
                                        ^
                                        |
[Pergunta do usuário] --> RAG Query Module --retrieval--+
                                |
                                v
                         Ollama (LLM) --> Resposta + fontes
                                |
                    (se contexto insuficiente)
                                v
                    MCP Client --> Ferramentas externas

Clientes MCP externos --> MCP Server Module --> RAG Query Module
```
