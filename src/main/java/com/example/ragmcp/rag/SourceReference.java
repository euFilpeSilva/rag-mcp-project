package com.example.ragmcp.rag;

/**
 * Referência a um chunk de documento usado como fonte de uma resposta.
 *
 * @param fileName        nome do arquivo de origem (ex: "manual.pdf")
 * @param pageNumber       página (PDF) ou índice do chunk (TXT) de origem
 * @param similarityScore  quão parecido semanticamente esse chunk é com a
 *                         pergunta (1.0 = idêntico, 0.0 = nada parecido)
 */
public record SourceReference(
        String fileName,
        Integer pageNumber,
        double similarityScore
) {
}
