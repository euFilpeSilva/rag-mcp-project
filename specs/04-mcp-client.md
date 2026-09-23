# Spec 04 — Módulo Cliente MCP (MCP Client)

## Objetivo
Permitir que o RAG Query Module (spec 02) consuma ferramentas MCP externas
como fallback quando o contexto local (documentos ingeridos) for
insuficiente para responder a uma pergunta.

## Requisitos Funcionais
- RF01: O sistema deve se conectar a um ou mais servidores MCP externos
  configurados (ex: servidor MCP de filesystem, servidor MCP de web
  search), via stdio, na inicialização da aplicação.
- RF02: Quando `RagQueryService.ask` (spec 02) retornar
  `insufficientContext = true`, o orquestrador deve, antes de responder ao
  usuário final, tentar invocar uma ferramenta MCP externa relevante
  (ex: busca na web) para complementar a resposta.
- RF03: O sistema deve decidir qual ferramenta MCP externa invocar via
  function-calling do LLM (o LLM recebe a lista de ferramentas MCP
  disponíveis e escolhe uma, ou nenhuma).
- RF04: A resposta final ao usuário deve indicar claramente quando a
  informação veio de uma ferramenta externa (fallback) versus dos
  documentos locais.
- RF05: A lista de servidores MCP externos e suas ferramentas deve ser
  configurável via arquivo `mcp-servers-config.json` (fora do código).

## Requisitos Não-Funcionais
- RNF01: Se um servidor MCP externo estiver indisponível na inicialização,
  a aplicação deve subir normalmente (log de warning), apenas sem aquela
  ferramenta disponível.
- RNF02: Timeout de 10 segundos por chamada de ferramenta externa; em caso
  de timeout, informar ao usuário que o fallback não pôde ser concluído.

## Contrato (Configuração)
```json
// mcp-servers-config.json
{
  "servers": [
    {
      "name": "filesystem",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/data/docs"]
    },
    {
      "name": "web-search",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-brave-search"]
    }
  ]
}
```

## Contrato (Interface Java)
```java
public interface McpToolOrchestrator {

    /**
     * Tenta responder usando ferramentas MCP externas quando o RAG local
     * não teve contexto suficiente.
     */
    FallbackResult tryFallback(String question);
}

public record FallbackResult(
        boolean used,
        String toolName,       // null se used = false
        String answer,         // null se used = false
        String rawToolOutput
) {}
```

## Critérios de Aceite
- CA01: Configurando apenas o servidor `filesystem` e perguntando sobre um
  arquivo presente no diretório configurado (mas não ingerido no
  PGVector), o sistema deve usar a ferramenta MCP para localizar e usar o
  conteúdo do arquivo na resposta.
- CA02: Com nenhum servidor MCP externo configurado, `tryFallback` deve
  retornar `used = false` de forma segura, sem lançar exceção.
- CA03: Se um servidor externo configurado não conseguir iniciar, a
  aplicação deve logar aviso e continuar funcionando com os demais
  servidores disponíveis.
- CA04: A resposta final ao usuário, quando `used = true`, deve indicar a
  origem externa da informação (ex: prefixo "[Fonte externa: web-search]").

## Testes Esperados
- Testes de integração usando um servidor MCP de teste simples (mock),
  cobrindo CA01–CA04.
