# Spec 02 — Módulo de Consulta RAG (RAG Query)

## Objetivo
Receber uma pergunta em linguagem natural, recuperar os trechos mais
relevantes do PGVector, montar um prompt aumentado com esse contexto, e
gerar uma resposta via LLM (Ollama), citando as fontes utilizadas.

## Requisitos Funcionais
- RF01: O sistema deve aceitar uma pergunta em texto livre.
- RF02: O sistema deve buscar os *top-K* chunks mais similares (default
  K=4) via similaridade de cosseno no PGVector.
- RF03: O sistema deve montar um prompt contendo: instrução de sistema,
  os chunks recuperados como contexto, e a pergunta do usuário.
- RF04: O sistema deve chamar o modelo `llama3.1` via Ollama para gerar a
  resposta.
- RF05: A resposta retornada deve incluir a lista de fontes (nome do
  arquivo + página) usadas como contexto.
- RF06: Se nenhum chunk relevante for encontrado (similaridade abaixo de um
  limiar configurável, default 0.5), o sistema deve indicar explicitamente
  que não encontrou informação suficiente nos documentos locais — deixando
  claro que o módulo MCP Client (spec 04) pode ser acionado como fallback.
- RF07: O sistema deve manter histórico de conversa por sessão (in-memory,
  não persistido) para permitir perguntas de acompanhamento (follow-up).
- RF08: O prompt deve instruir o LLM a não usar conhecimento externo,
  suposições ou inferências fracas, e a citar as fontes no formato
  `[Fonte N]` quando formular afirmações factuais.
- RF09: Após a geração, o sistema deve validar se a resposta está
  fundamentada nos chunks recuperados. Se a resposta introduzir fatos não
  sustentados pelo contexto, especialmente valores numéricos divergentes, o
  sistema deve retornar `insufficientContext=true` em vez da resposta
  gerada.

## Requisitos Não-Funcionais
- RNF01: Tempo de resposta end-to-end (retrieval + geração) deve ser menor
  que 15 segundos em ambiente local para perguntas simples.
- RNF02: O sistema deve ser thread-safe para permitir múltiplas sessões
  concorrentes.

## Contrato (Interface Java)
```java
public interface RagQueryService {

    /**
     * Responde a uma pergunta usando RAG sobre os documentos ingeridos.
     */
    RagAnswer ask(String sessionId, String question);
}

public record RagAnswer(
        String answer,
        List<SourceReference> sources,
        boolean insufficientContext
) {}

public record SourceReference(
        String fileName,
        Integer pageNumber,
        double similarityScore
) {}
```

## Critérios de Aceite
- CA01: Dada uma pergunta cuja resposta está claramente contida nos
  documentos ingeridos, `RagAnswer.answer` deve conter informação
  relevante e `sources` não deve estar vazio.
- CA02: Dada uma pergunta sem relação com os documentos ingeridos,
  `insufficientContext` deve ser `true` e `answer` deve indicar
  explicitamente a ausência de contexto suficiente.
- CA03: Duas chamadas consecutivas com o mesmo `sessionId`, onde a segunda
  pergunta referencia a primeira (ex: "e sobre o capítulo 2?"), devem
  produzir uma resposta coerente com o histórico da sessão.
- CA04: `sources` retornadas devem corresponder exatamente aos chunks com
  maior similaridade de cosseno para a pergunta, validável via query direta
  ao PGVector.
  - CA05: Dada uma resposta gerada que adiciona fatos ausentes ou conflitantes
    com os chunks recuperados, `RagAnswer.insufficientContext` deve ser `true`
    e a resposta gerada não deve ser usada como resposta final.

  ## Testes Esperados
  - Testes de integração cobrindo CA01–CA05, usando um conjunto fixo de
  documentos de teste ingeridos previamente (fixtures em `src/test/resources`).
