package com.example.ragmcp.whatsapp;

import com.example.ragmcp.rag.RagAnswer;
import com.example.ragmcp.rag.RagQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook compatível com o Twilio WhatsApp Sandbox (spec 06). Traduz o
 * payload do Twilio para uma chamada ao RagQueryService e devolve a
 * resposta em TwiML.
 */
@RestController
@RequestMapping(path = "/webhook/whatsapp")
public class WhatsAppWebhookController {

    private static final String WHATSAPP_PREFIX = "whatsapp:";
    private static final int MAX_ANSWER_LENGTH = 1500;
    private static final String TRUNCATION_SUFFIX = "... (resposta truncada)";
    private static final String EMPTY_BODY_MESSAGE =
            "Não entendi sua mensagem. Pode reformular a pergunta?";

    private final RagQueryService ragQueryService;

    public WhatsAppWebhookController(RagQueryService ragQueryService) {
        this.ragQueryService = ragQueryService;
    }

    @PostMapping(path = "/twilio", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> receiveTwilioMessage(
            @RequestParam("From") String from,
            @RequestParam(value = "Body", required = false) String body) {

        String replyText = (body == null || body.isBlank())
                ? EMPTY_BODY_MESSAGE
                : answerFor(sessionIdFrom(from), body.trim());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/xml;charset=UTF-8")
                .body(toTwiml(replyText));
    }

    private String answerFor(String sessionId, String question) {
        RagAnswer answer = ragQueryService.ask(sessionId, question);
        return truncate(answer.answer());
    }

    private String sessionIdFrom(String from) {
        return from.startsWith(WHATSAPP_PREFIX) ? from.substring(WHATSAPP_PREFIX.length()) : from;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_ANSWER_LENGTH) {
            return text;
        }
        int cutoff = MAX_ANSWER_LENGTH - TRUNCATION_SUFFIX.length();
        return text.substring(0, Math.max(0, cutoff)) + TRUNCATION_SUFFIX;
    }

    private String toTwiml(String message) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response><Message>"
                + escapeXml(message)
                + "</Message></Response>";
    }

    private String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
