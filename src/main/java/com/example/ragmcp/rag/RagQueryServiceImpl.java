package com.example.ragmcp.rag;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Implementação do fluxo central de RAG (spec 02): recebe uma pergunta,
 * busca contexto relevante no banco vetorial, monta um prompt aumentado
 * e pede ao LLM uma resposta — sempre tentando garantir que a resposta
 * seja fundamentada nos documentos (e não "inventada" pelo modelo).
 *
 * <p>Resumo do fluxo de {@link #ask(String, String)}, passo a passo:</p>
 * <ol>
 *   <li><b>Retrieval</b>: transforma a pergunta em embedding e busca no
 *       PGVector os chunks mais similares (ver {@link #retrieveMatches}).</li>
 *   <li><b>Guarda de contexto vazio</b>: se nenhum chunk passar do
 *       limiar de similaridade configurado, retorna direto uma resposta
 *       de "não sei", sem nem chamar o LLM (ver {@link #insufficientContext()}).</li>
 *   <li><b>Montagem do prompt</b>: concatena o histórico da conversa
 *       (memória por sessão), o contexto recuperado e a pergunta, e
 *       chama o {@code ChatModel} (Ollama).</li>
 *   <li><b>Verificação anti-alucinação</b>: depois que o LLM responde,
 *       o código confere se os termos "importantes" da resposta também
 *       aparecem no contexto recuperado (ver {@link #isAnswerGrounded}).
 *       Se a resposta parecer ter "inventado" fatos não sustentados
 *       pelo contexto (por exemplo, um número diferente do que está no
 *       documento), a resposta é descartada e trocada por uma mensagem
 *       de "não sei" — isso reduz (mas não elimina 100%) o risco de
 *       alucinação do LLM.</li>
 *   <li><b>Memória de conversa</b>: só depois de aprovada a resposta,
 *       ela é salva no histórico da sessão, permitindo perguntas de
 *       acompanhamento ("E sobre o capítulo 2?").</li>
 * </ol>
 */
@Service
public class RagQueryServiceImpl implements RagQueryService {

    private static final String SOURCE_FILE = "source_file";
    private static final String PAGE_NUMBER = "page_number";
    private static final String INSUFFICIENT_CONTEXT_MESSAGE = "Não encontrei contexto suficiente nos documentos locais "
            + "para responder com segurança. Um fallback MCP externo pode ser acionado.";
    /**
     * Instrução de sistema enviada ao LLM em toda conversa. É a primeira
     * linha de defesa contra alucinação: pede explicitamente para o
     * modelo não usar conhecimento externo e citar as fontes usadas.
     * Sozinha, essa instrução NÃO garante que o modelo obedeça sempre —
     * por isso existe também a verificação pós-resposta ({@link #isAnswerGrounded}).
     */
    private static final String SYSTEM_PROMPT = """
            Você é um assistente RAG estritamente fundamentado em evidências.
            Responda apenas com fatos presentes no contexto recuperado.
            Não use conhecimento externo, suposições, inferências fracas ou memória da conversa como fonte factual.
            Se o contexto não contiver informação suficiente, responda exatamente:
            "Não encontrei contexto suficiente nos documentos locais para responder com segurança."
            Cite as fontes usadas no formato [Fonte N] ao lado das afirmações principais.
            """;
    /**
     * Palavras comuns em português (artigos, preposições, e também
     * palavras do próprio "esqueleto" das mensagens do sistema, como
     * "contexto"/"fonte") que são ignoradas ao comparar resposta vs.
     * contexto — sem isso, qualquer resposta pareceria "não fundamentada"
     * só por usar conectivos naturais da língua.
     */
    private static final Set<String> ANSWER_STOP_WORDS = Set.of(
            "a", "o", "as", "os", "e", "de", "do", "da", "das", "dos", "um", "uma", "uns", "umas",
            "para", "por", "com", "sem", "em", "no", "na", "nos", "nas", "ao", "aos", "que", "se",
            "sua", "seu", "suas", "seus", "isso", "esta", "este", "essas", "esses", "sobre", "como",
            "qual", "quais", "quando", "onde", "porque", "resposta", "baseada", "contexto", "fonte",
            "fontes", "documento", "documentos", "locais", "recuperado", "recuperados", "usuario",
            "pergunta", "seguranca", "suficiente", "encontrei", "externo", "fallback", "acionado",
            "mensagem", "messages");

    private final ChatModel chatModel;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    /**
     * Guarda o histórico de conversa de cada sessão (chave = sessionId).
     * É apenas em memória (não persistido em banco) — se a aplicação
     * reiniciar, todo o histórico de conversas se perde (RF07 da spec 02).
     */
    private final ConcurrentMap<String, ChatMemory> memories = new ConcurrentHashMap<>();
    /** Quantos chunks buscar por padrão (top-K), vindo de application.yml. */
    private final int defaultTopK;
    /** Limiar mínimo de similaridade (0 a 1) para um chunk ser considerado relevante. */
    private final double minSimilarity;

    public RagQueryServiceImpl(
            ChatModel chatModel,
            EmbeddingModel embeddingModel,
            EmbeddingStore<TextSegment> embeddingStore,
            @Value("${rag.retrieval.top-k}") int defaultTopK,
            @Value("${rag.retrieval.min-similarity}") double minSimilarity) {
        this.chatModel = chatModel;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.defaultTopK = defaultTopK;
        this.minSimilarity = minSimilarity;
    }

    /**
     * Método principal do RAG: pergunta em texto livre entra, resposta
     * fundamentada (ou "não sei") sai. Ver a documentação da classe para
     * o passo a passo completo do fluxo.
     */
    @Override
    public RagAnswer ask(String sessionId, String question) {
        // 1) Busca os chunks mais parecidos com a pergunta no banco vetorial.
        List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(question, defaultTopK, minSimilarity);
        List<SourceReference> sources = toSources(matches);
        if (sources.isEmpty()) {
            // Nenhum chunk passou do limiar de similaridade: nem vale a pena
            // chamar o LLM, pois não há contexto confiável para responder.
            return insufficientContext();
        }

        // 2) Recupera (ou cria) a memória de conversa desta sessão/usuário.
        ChatMemory memory = memories.computeIfAbsent(sessionId, this::newMemory);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>(memory.messages());
        if (messages.isEmpty()) {
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
        }

        // 3) Monta o prompt aumentado: contexto recuperado + pergunta atual.
        String context = buildContext(matches);
        messages.add(UserMessage.from("""
                Contexto recuperado:
                %s

                Pergunta do usuário:
                %s
                """.formatted(context, question)));

        // 4) Chama o LLM (Ollama) para gerar a resposta em texto natural.
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .build();
        AiMessage aiMessage = chatModel.chat(request).aiMessage();

        // 5) Verificação anti-alucinação: só aceita a resposta se ela parecer
        // realmente apoiada no contexto recuperado (ver isAnswerGrounded).
        String answer = aiMessage.text();
        if (!isAnswerGrounded(answer, context)) {
            return insufficientContext();
        }

        // 6) Só agora a pergunta e a resposta entram no histórico da sessão —
        // se a resposta tivesse sido rejeitada acima, não "contaminaria" a
        // memória para perguntas futuras de acompanhamento.
        if (memory.messages().isEmpty()) {
            memory.add(SystemMessage.from(SYSTEM_PROMPT));
        }
        memory.add(UserMessage.from(question));
        memory.add(aiMessage);

        return new RagAnswer(ensureSourceNotice(answer, sources), sources, false);
    }

    /**
     * Busca "crua" (sem passar pelo LLM): usada pelo servidor MCP na
     * ferramenta {@code search_documents}, para quem quer só ver os
     * trechos relevantes sem gerar uma resposta em linguagem natural.
     */
    @Override
    public List<SourceReference> search(String query, int topK) {
        return toSources(retrieveMatches(query, topK, minSimilarity));
    }

    /** Cria uma memória de conversa nova, limitada às últimas 20 mensagens (janela deslizante). */
    private ChatMemory newMemory(String sessionId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(20)
                .build();
    }

    /**
     * Faz a busca por similaridade propriamente dita: transforma a
     * pergunta em embedding (vetor) e delega ao {@code EmbeddingStore}
     * (PGVector) a query SQL de similaridade de cosseno. Reordena os
     * resultados por score decrescente (mais similar primeiro).
     */
    private List<EmbeddingMatch<TextSegment>> retrieveMatches(String question, int topK, double threshold) {
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(question).content())
                .maxResults(topK)
                .minScore(threshold)
                .build();
        return embeddingStore.search(request).matches().stream()
                .sorted(Comparator.comparing(EmbeddingMatch<TextSegment>::score).reversed())
                .toList();
    }

    /**
     * Monta o bloco de texto "Contexto recuperado" que entra no prompt,
     * numerando cada chunk como {@code [Fonte N: ...]} — esse número é o
     * mesmo que o prompt de sistema pede para o LLM citar nas respostas.
     */
    private String buildContext(List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            Metadata metadata = match.embedded().metadata();
            String fileName = metadata != null ? metadata.getString(SOURCE_FILE) : "desconhecido";
            Integer pageNumber = metadata != null ? metadata.getInteger(PAGE_NUMBER) : null;
            if (!context.isEmpty()) {
                context.append("\n\n");
            }
            context.append("[Fonte %d: arquivo=%s, pagina=%s, score=%.4f]%n%s"
                    .formatted(i + 1, fileName, pageNumber, match.score(), match.embedded().text()));
        }
        return context.toString();
    }

    /** Converte os matches internos do LangChain4j em {@link SourceReference}, expostos na API. */
    private List<SourceReference> toSources(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .map(match -> {
                    Metadata metadata = match.embedded().metadata();
                    return new SourceReference(
                            metadata != null ? metadata.getString(SOURCE_FILE) : null,
                            metadata != null ? metadata.getInteger(PAGE_NUMBER) : null,
                            match.score());
                })
                .toList();
    }

    /** Resposta padrão de "não sei", usada tanto quando não há contexto quanto quando a resposta é rejeitada. */
    private RagAnswer insufficientContext() {
        return new RagAnswer(INSUFFICIENT_CONTEXT_MESSAGE, List.of(), true);
    }

    /**
     * Heurística anti-alucinação: compara as palavras "significativas" da
     * resposta gerada com as palavras presentes no contexto recuperado.
     *
     * <p>Regras:</p>
     * <ul>
     *   <li>Se a resposta contiver qualquer número que não apareça no
     *       contexto, ela é rejeitada na hora — números divergentes (ex:
     *       "24 meses" quando o documento diz "12 meses") são o tipo de
     *       alucinação mais perigoso de deixar passar.</li>
     *   <li>Para as demais palavras, é tolerado até ~35% (mínimo 2
     *       palavras) de termos que não aparecem no contexto — isso dá
     *       margem para o LLM reformular/resumir com sinônimos sem ser
     *       penalizado por não repetir literalmente o texto original.</li>
     * </ul>
     *
     * <p>Isso é uma heurística simples baseada em palavras-chave, não uma
     * verificação semântica de verdade — não é 100% à prova de falhas,
     * mas bloqueia os casos mais óbvios de invenção de fatos.</p>
     */
    private boolean isAnswerGrounded(String answer, String context) {
        if (answer == null || answer.isBlank()) {
            return false;
        }
        if (answer.contains("Não encontrei contexto suficiente")) {
            return false;
        }

        Set<String> contextTerms = significantTerms(context);
        Set<String> answerTerms = significantTerms(answer);
        if (answerTerms.isEmpty()) {
            return false;
        }

        int unsupportedTerms = 0;
        for (String term : answerTerms) {
            if (isNumeric(term) && !contextTerms.contains(term)) {
                return false;
            }
            if (!contextTerms.contains(term)) {
                unsupportedTerms++;
            }
        }

        int allowedUnsupportedTerms = Math.max(2, (int) Math.ceil(answerTerms.size() * 0.35d));
        return unsupportedTerms <= allowedUnsupportedTerms;
    }

    /**
     * Normaliza um texto (minúsculas, remove pontuação/marcadores de fonte)
     * e extrai o conjunto de palavras "significativas" (ignora stopwords e
     * palavras muito curtas, exceto números).
     */
    private Set<String> significantTerms(String text) {
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("\\[fonte\\s+\\d+]", " ")
                .replaceAll("\\[messages=\\d+]", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", " ");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank() || ANSWER_STOP_WORDS.contains(token)) {
                continue;
            }
            if (token.length() <= 2 && !isNumeric(token)) {
                continue;
            }
            terms.add(token);
        }
        return terms;
    }

    private boolean isNumeric(String value) {
        return value.chars().allMatch(Character::isDigit);
    }

    /**
     * Se o LLM não citou nenhuma fonte no formato {@code [Fonte N]} (apesar
     * de instruído a fazer isso), anexa manualmente uma lista simples dos
     * arquivos/páginas consultados ao final da resposta — garante que o
     * usuário sempre veja de onde a informação veio.
     */
    private String ensureSourceNotice(String answer, List<SourceReference> sources) {
        if (answer.contains("[Fonte")) {
            return answer;
        }
        String sourceNotice = sources.stream()
                .limit(3)
                .map(source -> "%s%s".formatted(
                        source.fileName() == null ? "fonte desconhecida" : source.fileName(),
                        source.pageNumber() == null ? "" : " p. " + source.pageNumber()))
                .collect(Collectors.joining("; "));
        return answer + "\n\nFontes consultadas: " + sourceNotice;
    }
}
