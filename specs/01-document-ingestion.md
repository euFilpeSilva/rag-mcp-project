# Spec 01 — Módulo de Ingestão de Documentos

## Objetivo
Permitir carregar documentos (PDF ou TXT) de um diretório local, dividi-los
em chunks, gerar embeddings via Ollama e persistir no PGVector para uso
posterior pelo módulo de RAG Query.

## Requisitos Funcionais
- RF01: O sistema deve aceitar arquivos `.pdf` e `.txt` como entrada.
- RF02: O sistema deve dividir o texto extraído em chunks de tamanho
  configurável (default: 500 tokens, overlap 50 tokens).
- RF03: Cada chunk deve gerar um embedding vetorial usando o modelo
  `nomic-embed-text` via Ollama.
- RF04: Cada chunk armazenado deve manter metadados: nome do arquivo
  original, número da página (se PDF) ou offset, e um hash de conteúdo para
  evitar duplicação.
- RF05: Reingestão do mesmo arquivo (mesmo hash) deve ser idempotente — não
  duplicar embeddings já existentes.
- RF06: O sistema deve expor um método/endpoint para ingestão de um único
  arquivo e outro para ingestão em lote de um diretório.

## Requisitos Não-Funcionais
- RNF01: Ingestão de um PDF de até 50 páginas deve completar em menos de
  60 segundos em ambiente local (Ollama + Postgres em Docker).
- RNF02: Falhas de parsing em um arquivo não devem interromper a ingestão
  em lote dos demais arquivos — devem ser logadas e reportadas ao final.

## Contrato (Interface Java)
```java
public interface DocumentIngestionService {

    /**
     * Ingere um único arquivo (PDF ou TXT).
     * @return relatório com número de chunks criados/ignorados (duplicados)
     */
    IngestionResult ingestFile(Path filePath);

    /**
     * Ingere todos os arquivos suportados de um diretório (não recursivo).
     */
    List<IngestionResult> ingestDirectory(Path directoryPath);
}

public record IngestionResult(
        String fileName,
        int chunksCreated,
        int chunksSkippedAsDuplicate,
        boolean success,
        String errorMessage // null se success = true
) {}
```

## Schema da Tabela (PGVector)
```sql
CREATE TABLE IF NOT EXISTS document_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL, -- dimensão do nomic-embed-text
    source_file TEXT NOT NULL,
    page_number INT,
    chunk_hash TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT now()
);
```

## Critérios de Aceite
- CA01: Dado um arquivo `sample.pdf` de 3 páginas, ao ingerir, o número de
  chunks criados deve ser maior que zero e cada chunk deve ter um embedding
  de dimensão 768.
- CA02: Ao ingerir o mesmo arquivo duas vezes, a segunda execução deve
  retornar `chunksCreated = 0` e `chunksSkippedAsDuplicate` igual ao total
  de chunks do arquivo.
- CA03: Ao ingerir um diretório com um arquivo corrompido e dois válidos,
  o relatório deve conter 3 resultados, sendo 1 com `success = false` e 2
  com `success = true`.
- CA04: Metadados `source_file` e `page_number` devem estar corretamente
  preenchidos e recuperáveis via query direta na tabela `document_chunks`.

## Testes Esperados
- Teste de integração usando Testcontainers (Postgres+pgvector) e um mock
  ou instância real do Ollama, cobrindo CA01–CA04.
