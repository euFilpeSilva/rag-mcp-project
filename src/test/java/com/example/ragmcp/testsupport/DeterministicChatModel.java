package com.example.ragmcp.testsupport;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

public class DeterministicChatModel implements ChatModel {

    private final List<ChatRequest> recordedRequests = new CopyOnWriteArrayList<>();
    private String forcedResponse;

    @Override
    public ChatResponse doChat(ChatRequest request) {
        recordedRequests.add(request);
        return ChatResponse.builder()
                .modelName("deterministic-test-chat")
                .aiMessage(AiMessage.from(generateAnswer(request)))
                .build();
    }

    public List<ChatRequest> recordedRequests() {
        return Collections.unmodifiableList(recordedRequests);
    }

    public void clear() {
        recordedRequests.clear();
        forcedResponse = null;
    }

    public void forceResponse(String forcedResponse) {
        this.forcedResponse = forcedResponse;
    }

    private String generateAnswer(ChatRequest request) {
        if (forcedResponse != null) {
            return forcedResponse;
        }

        List<ChatMessage> messages = request.messages() == null ? List.of() : request.messages();
        UserMessage lastUserMessage = UserMessage.findLast(messages).orElse(UserMessage.from(""));
        String prompt = lastUserMessage.singleText();
        int messageCount = messages.size();

        if (prompt.contains("Contexto recuperado:")) {
            String question = between(prompt, "Pergunta do usuário:", null).trim();
            List<String> contextLines = extractContextLines(prompt);
            String selectedLine = selectContextLine(contextLines, question);
            return "Resposta baseada no contexto: " + selectedLine + " [messages=" + messageCount + "]";
        }

        return "Resposta determinística [messages=" + messageCount + "]";
    }

    private List<String> extractContextLines(String prompt) {
        String context = between(prompt, "Contexto recuperado:", "Pergunta do usuário:");
        List<String> lines = new ArrayList<>();
        for (String rawLine : context.split("\\R")) {
            String line = rawLine.trim();
            if (!line.isBlank() && !line.startsWith("[arquivo=") && !line.startsWith("[Fonte ")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private String selectContextLine(List<String> contextLines, String question) {
        String normalizedQuestion = normalize(question);
        for (String line : contextLines) {
            String normalizedLine = normalize(line);
            for (String token : normalizedQuestion.split("\\s+")) {
                if (token.length() > 2 && normalizedLine.contains(token)) {
                    return line;
                }
            }
        }
        return contextLines.isEmpty() ? "Sem contexto" : contextLines.getFirst();
    }

    private String between(String text, String startToken, String endToken) {
        int start = text.indexOf(startToken);
        if (start < 0) {
            return "";
        }
        start += startToken.length();
        int end = endToken == null ? text.length() : text.indexOf(endToken, start);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
    }
}
