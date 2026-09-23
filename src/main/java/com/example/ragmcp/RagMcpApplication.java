package com.example.ragmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada da aplicação (fins de estudo).
 *
 * <p>Este é um chatbot RAG (Retrieval-Augmented Generation): em vez de o
 * LLM "adivinhar" a resposta usando apenas seu conhecimento treinado, o
 * sistema primeiro busca trechos relevantes de documentos próprios
 * (PDF/TXT) e injeta esses trechos no prompt, para que o modelo responda
 * com base em fatos concretos.</p>
 *
 * <p>Visão geral do fluxo principal (ver cada pacote para detalhes):</p>
 * <ol>
 *   <li><b>ingestion</b> — lê PDFs/TXTs, quebra em pedaços (chunks),
 *       gera embeddings (vetores numéricos) e salva no PGVector.</li>
 *   <li><b>rag</b> — recebe uma pergunta, busca os chunks mais
 *       parecidos (similaridade de cosseno) e pede ao LLM (Ollama) uma
 *       resposta baseada apenas nesses trechos.</li>
 *   <li><b>mcpserver</b> — expõe esse RAG como "ferramentas" MCP (Model
 *       Context Protocol), para que clientes externos (ex: Claude
 *       Desktop) possam usá-lo.</li>
 *   <li><b>mcpclient</b> — faz o caminho inverso: quando o RAG local não
 *       tem contexto suficiente, tenta usar ferramentas MCP externas
 *       (ex: um servidor de filesystem) como fallback.</li>
 *   <li><b>api</b> — expõe tudo isso via REST (HTTP) para testes
 *       manuais com curl/Postman.</li>
 *   <li><b>config</b> — "cola" (beans) que conecta o LangChain4j ao
 *       Ollama (LLM/embeddings) e ao Postgres+PGVector (banco vetorial).</li>
 * </ol>
 *
 * <p>{@code @SpringBootApplication} é uma anotação "combo" do Spring Boot
 * que ativa auto-configuração, escaneamento de componentes (todas as
 * classes anotadas com {@code @Service}, {@code @RestController},
 * {@code @Component}, {@code @Configuration} dentro deste pacote e
 * subpacotes são registradas automaticamente) e permite rodar a
 * aplicação como um programa Java comum.</p>
 */
@SpringBootApplication
public class RagMcpApplication {

    /**
     * Sobe o contexto do Spring (cria todos os beans, conecta ao banco,
     * inicia o servidor web embutido na porta 8080) e mantém a aplicação
     * rodando até ser interrompida (Ctrl+C).
     */
    public static void main(String[] args) {
        SpringApplication.run(RagMcpApplication.class, args);
    }
}
