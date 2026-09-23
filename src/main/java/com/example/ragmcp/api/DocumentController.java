package com.example.ragmcp.api;

import com.example.ragmcp.ingestion.DocumentIngestionService;
import com.example.ragmcp.ingestion.IngestionResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Endpoints de ingestão de documentos: {@code POST /api/documents}
 * (upload avulso de um arquivo) e {@code POST /api/documents/batch}
 * (ingestão de todos os arquivos de uma pasta do servidor).
 */
@Validated
@RestController
@RequestMapping(path = "/api/documents", produces = MediaType.APPLICATION_JSON_VALUE)
public class DocumentController {

    private final DocumentIngestionService documentIngestionService;

    public DocumentController(DocumentIngestionService documentIngestionService) {
        this.documentIngestionService = documentIngestionService;
    }

    /**
     * Recebe um arquivo via multipart/form-data, salva temporariamente em
     * {@code target/uploads}, delega a ingestão de fato ao serviço e, no
     * fim (sucesso ou falha), remove o arquivo temporário.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestionResult upload(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty() || file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()) {
            throw new IllegalArgumentException("Arquivo multipart é obrigatório");
        }

        Path uploadDir = Path.of(System.getProperty("user.dir"), "target", "uploads");
        Path tempFile = uploadDir.resolve(UUID.randomUUID() + "-" + file.getOriginalFilename());
        try {
            Files.createDirectories(uploadDir);
            file.transferTo(tempFile);
            return documentIngestionService.ingestFile(tempFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao salvar upload", e);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
            }
        }
    }

    /** Ingesta em lote todos os arquivos suportados dentro de um diretório já existente no servidor. */
    @PostMapping(path = "/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public List<IngestionResult> batch(@Valid @RequestBody BatchIngestionRequest request) {
        return documentIngestionService.ingestDirectory(Path.of(request.directoryPath()));
    }

    public record BatchIngestionRequest(@NotBlank String directoryPath) {
    }
}
