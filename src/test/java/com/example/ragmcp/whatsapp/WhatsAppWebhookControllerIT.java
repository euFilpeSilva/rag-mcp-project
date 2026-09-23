package com.example.ragmcp.whatsapp;

import com.example.ragmcp.ingestion.DocumentIngestionService;
import com.example.ragmcp.testsupport.AbstractPgVectorIntegrationTest;
import com.example.ragmcp.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@AutoConfigureMockMvc
class WhatsAppWebhookControllerIT extends AbstractPgVectorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    @BeforeEach
    void ingestFixtures() {
        documentIngestionService.ingestFile(TestFixtures.samplePdf());
        documentIngestionService.ingestFile(TestFixtures.sampleTxt());
    }

    @Test
    void shouldAnswerQuestionUsingRagAndReturnTwiml() throws Exception {
        // CA01 spec 06
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", "whatsapp:+5511999999999");
        form.add("Body", "Qual e a garantia do produto X?");

        mockMvc.perform(post("/webhook/whatsapp/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(form))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_XML))
                .andExpect(content().string(containsString("<Response><Message>")))
                .andExpect(content().string(containsString("12 meses")));
    }

    @Test
    void shouldPreserveSessionHistoryPerPhoneNumber() throws Exception {
        // CA02 spec 06
        MultiValueMap<String, String> firstForm = new LinkedMultiValueMap<>();
        firstForm.add("From", "whatsapp:+5511988887777");
        firstForm.add("Body", "Qual e a garantia do produto X?");

        MultiValueMap<String, String> secondForm = new LinkedMultiValueMap<>();
        secondForm.add("From", "whatsapp:+5511988887777");
        secondForm.add("Body", "E sobre o capitulo 2?");

        mockMvc.perform(post("/webhook/whatsapp/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(firstForm))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("12 meses")));

        mockMvc.perform(post("/webhook/whatsapp/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(secondForm))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("220V")));

        int firstMessageCount = deterministicChatModel.recordedRequests().get(0).messages().size();
        int secondMessageCount = deterministicChatModel.recordedRequests().get(1).messages().size();
        org.assertj.core.api.Assertions.assertThat(secondMessageCount).isGreaterThan(firstMessageCount);
    }

    @Test
    void shouldReturnDefaultMessageWhenBodyIsBlank() throws Exception {
        // CA03 spec 06
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", "whatsapp:+5511977776666");
        form.add("Body", "   ");

        mockMvc.perform(post("/webhook/whatsapp/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(form))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Não entendi sua mensagem")));

        org.assertj.core.api.Assertions.assertThat(deterministicChatModel.recordedRequests()).isEmpty();
    }

    @Test
    void shouldEscapeXmlSpecialCharactersInAnswer() throws Exception {
        // CA04 spec 06
        // Mantém termos presentes no contexto (garantia, 12, meses) para não ser
        // rejeitada pela verificação anti-alucinação, mas inclui caracteres
        // especiais de XML para validar o escaping.
        deterministicChatModel.forceResponse(
                "A garantia & <observacao> \"especial\" do produto e de 12 meses.");

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("From", "whatsapp:+5511966665555");
        form.add("Body", "Qual e a garantia do produto X?");

        String responseBody = mockMvc.perform(post("/webhook/whatsapp/twilio")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .params(form))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(responseBody)
                .contains("&amp;")
                .contains("&lt;observacao&gt;")
                .contains("&quot;especial&quot;");

        // Deve ser XML válido (não deve lançar exceção ao parsear)
        javax.xml.parsers.DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(responseBody)));
    }
}
