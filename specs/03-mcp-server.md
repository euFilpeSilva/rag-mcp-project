# Spec 03 — Módulo Servidor MCP (MCP Server)

## Objetivo
Expor as capacidades do RAG (busca e pergunta) como ferramentas MCP
(Model Context Protocol), permitindo que clientes MCP externos (ex: Claude
Desktop, IDEs com suporte a MCP) consultem os documentos ingeridos.

## Requisitos Funcionais
- RF01: O servidor MCP deve expor a ferramenta `ask_question`, que recebe
  `{ question: string, sessionId?: string }` e retorna um `RagAnswer`
  (spec 02) serializado em JSON.
- RF02: O servidor MCP deve expor a ferramenta `search_documents`, que
  recebe `{ query: string, topK?: int }` e retorna a lista de
  `SourceReference` (chunks + score), sem chamar o LLM — apenas retrieval.
- RF03: O servidor MCP deve expor a ferramenta `ingest_document`, que
  recebe `{ filePath: string }` e retorna um `IngestionResult` (spec 01).
- RF04: O servidor deve rodar via transporte **stdio** (padrão MCP para
  integração com clientes desktop) e, opcionalmente, via **SSE/HTTP** para
  testes locais.
- RF05: Cada ferramenta exposta deve ter uma descrição clara (`description`)
  e schema de parâmetros (`inputSchema`) conforme especificação MCP, para
  que LLMs clientes entendam quando invocá-las.

## Requisitos Não-Funcionais
- RNF01: Erros internos (ex: falha ao conectar no Postgres) devem ser
  reportados como erro de ferramenta MCP (`isError: true`), nunca causar
  crash do processo do servidor.
- RNF02: O servidor deve logar todas as chamadas de ferramentas (nome,
  parâmetros, duração) em stderr (stdout é reservado ao protocolo MCP).

## Contrato (Definição das Ferramentas MCP)
```json
[
  {
    "name": "ask_question",
    "description": "Responde a uma pergunta usando RAG sobre os documentos locais ingeridos.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "question": { "type": "string" },
        "sessionId": { "type": "string" }
      },
      "required": ["question"]
    }
  },
  {
    "name": "search_documents",
    "description": "Busca trechos de documentos relevantes para uma consulta, sem gerar resposta via LLM.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "query": { "type": "string" },
        "topK": { "type": "integer", "default": 4 }
      },
      "required": ["query"]
    }
  },
  {
    "name": "ingest_document",
    "description": "Ingere um novo arquivo PDF ou TXT na base vetorial.",
    "inputSchema": {
      "type": "object",
      "properties": {
        "filePath": { "type": "string" }
      },
      "required": ["filePath"]
    }
  }
]
```

## Critérios de Aceite
- CA01: Um cliente MCP conectado via stdio deve conseguir listar as 3
  ferramentas acima via `tools/list`.
- CA02: Chamar `ask_question` via MCP com uma pergunta válida deve retornar
  o mesmo resultado que chamar `RagQueryService.ask` diretamente.
- CA03: Chamar `ingest_document` com um caminho inexistente deve retornar
  `isError: true` com mensagem descritiva, sem derrubar o servidor.
- CA04: `search_documents` deve retornar resultados ordenados por score de
  similaridade decrescente.

## Testes Esperados
- Testes de integração usando um cliente MCP de teste (ex: SDK MCP em
  modo cliente) conectando ao servidor via stdio/processo local,
  cobrindo CA01–CA04.
