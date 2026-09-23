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

@Service
public class RagQueryServiceImpl implements RagQueryService {

    private static final String SOURCE_FILE = "source_file";
    private static final String PAGE_NUMBER = "page_number";
    private static final String INSUFFICIENT_CONTEXT_MESSAGE = "Não encontrei contexto suficiente nos documentos locais "
            + "para responder com segurança. Um fallback MCP externo pode ser acionado.";
    private static final String SYSTEM_PROMPT = """
            Você é um assistente RAG estritamente fundamentado em evidências.
            Responda apenas com fatos presentes no contexto recuperado.
            Não use conhecimento externo, suposições, inferências fracas ou memória da conversa como fonte factual.
            Se o contexto não contiver informação suficiente, responda exatamente:
            "Não encontrei contexto suficiente nos documentos locais para responder com segurança."
            Cite as fontes usadas no formato [Fonte N] ao lado das afirmações principais.
            """;
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
    private final ConcurrentMap<String, ChatMemory> memories = new ConcurrentHashMap<>();
    private final int defaultTopK;
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

    @Override
    public RagAnswer ask(String sessionId, String question) {
        List<EmbeddingMatch<TextSegment>> matches = retrieveMatches(question, defaultTopK, minSimilarity);
        List<SourceReference> sources = toSources(matches);
        if (sources.isEmpty()) {
            return insufficientContext();
        }

        ChatMemory memory = memories.computeIfAbsent(sessionId, this::newMemory);
        List<dev.langchain4j.data.message.ChatMessage> messages = new ArrayList<>(memory.messages());
        if (messages.isEmpty()) {
            messages.add(SystemMessage.from(SYSTEM_PROMPT));
        }

        String context = buildContext(matches);
        messages.add(UserMessage.from("""
                Contexto recuperado:
                %s

                Pergunta do usuário:
                %s
                """.formatted(context, question)));

        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .build();
        AiMessage aiMessage = chatModel.chat(request).aiMessage();

        String answer = aiMessage.text();
        if (!isAnswerGrounded(answer, context)) {
            return insufficientContext();
        }

        if (memory.messages().isEmpty()) {
            memory.add(SystemMessage.from(SYSTEM_PROMPT));
        }
        memory.add(UserMessage.from(question));
        memory.add(aiMessage);

        return new RagAnswer(ensureSourceNotice(answer, sources), sources, false);
    }

    @Override
    public List<SourceReference> search(String query, int topK) {
        return toSources(retrieveMatches(query, topK, minSimilarity));
    }

    private ChatMemory newMemory(String sessionId) {
        return MessageWindowChatMemory.builder()
                .id(sessionId)
                .maxMessages(20)
                .build();
    }

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

    private RagAnswer insufficientContext() {
        return new RagAnswer(INSUFFICIENT_CONTEXT_MESSAGE, List.of(), true);
    }

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
