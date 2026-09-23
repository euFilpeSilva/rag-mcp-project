package com.example.ragmcp.api;

import com.example.ragmcp.rag.RagAnswer;
import com.example.ragmcp.rag.RagQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint HTTP principal do projeto: {@code POST /api/chat}. Recebe
 * uma pergunta em linguagem natural (e um {@code sessionId} para manter
 * histórico de conversa) e devolve a resposta gerada via RAG.
 */
@RestController
@RequestMapping(path = "/api/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final RagQueryService ragQueryService;

    public ChatController(RagQueryService ragQueryService) {
        this.ragQueryService = ragQueryService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public RagAnswer chat(@Valid @RequestBody ChatRequest request) {
        return ragQueryService.ask(request.sessionId(), request.question());
    }

    /** Corpo esperado da requisição: identificador de sessão + pergunta, ambos obrigatórios. */
    public record ChatRequest(@NotBlank String sessionId, @NotBlank String question) {
    }
}
