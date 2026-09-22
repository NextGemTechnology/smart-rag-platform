package com.nextgem.smartrag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgem.smartrag.vectorstore.ChromaVectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Enterprise RAG generation service that assembles retrieved context chunks
 * into citations and generates grounded answers via LLM or extractive synthesis.
 */
@Service
public class RagGenerationService {

    private static final Logger log = LoggerFactory.getLogger(RagGenerationService.class);

    private final ChromaVectorStoreService vectorStoreService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${rag.llm.endpoint:http://localhost:11434/api/generate}")
    private String llmEndpoint;

    @Value("${rag.llm.model:llama3.2}")
    private String llmModel;

    @Value("${rag.llm.gemini-api-key:}")
    private String geminiApiKey;

    public RagGenerationService(
            ChromaVectorStoreService vectorStoreService,
            ObjectMapper objectMapper
    ) {
        this.vectorStoreService = vectorStoreService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Answers a query using context retrieved from the vector store.
     */
    public RagAnswer ask(String query, int topK) {
        List<ChromaVectorStoreService.SearchResult> searchResults = vectorStoreService.search(query, topK);

        if (searchResults.isEmpty()) {
            return new RagAnswer(
                    query,
                    "No relevant document context found in the vector index. Please ingest documents first.",
                    List.of(),
                    0
            );
        }

        List<Citation> citations = new ArrayList<>();
        StringBuilder contextBuilder = new StringBuilder();

        for (int i = 0; i < searchResults.size(); i++) {
            ChromaVectorStoreService.SearchResult res = searchResults.get(i);
            String docLabel = (res.sources() != null && res.sources().size() > 1)
                    ? res.document() + " (also in: " + String.join(", ", res.sources()) + ")"
                    : res.document();

            citations.add(new Citation(
                    docLabel,
                    res.page(),
                    res.heading(),
                    res.score(),
                    res.text().substring(0, Math.min(250, res.text().length())) + "..."
            ));

            contextBuilder.append(String.format("[%d] Document: %s | Page: %d | Section: %s\n%s\n\n",
                    i + 1, docLabel, res.page(), res.heading(), res.text()));
        }

        String answer;
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            answer = callGeminiLlm(query, contextBuilder.toString());
        } else {
            // Attempt local Ollama endpoint, or fall back to context summary
            answer = callOllamaOrFallback(query, contextBuilder.toString(), searchResults);
        }

        return new RagAnswer(query, answer, citations, searchResults.size());
    }

    private String callGeminiLlm(String query, String context) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiApiKey;
            String prompt = buildSystemPrompt(query, context);

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                return root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText();
            }
        } catch (Exception e) {
            log.warn("Gemini API call failed: {}", e.getMessage());
        }
        return "Grounded Answer based on retrieved records:\n\n" + context;
    }

    private String callOllamaOrFallback(String query, String context, List<ChromaVectorStoreService.SearchResult> results) {
        try {
            String prompt = buildSystemPrompt(query, context);
            Map<String, Object> body = Map.of(
                    "model", llmModel,
                    "prompt", prompt,
                    "stream", false
            );

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(llmEndpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(5))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                return root.path("response").asText();
            }
        } catch (Exception ignored) {
            // Ollama offline, use extractive synthesis
        }

        // Extractive fallback
        StringBuilder sb = new StringBuilder();
        sb.append("### Extractive Retrieval Summary\n");
        sb.append("Based on the query: *\"").append(query).append("\"*\n\n");
        for (ChromaVectorStoreService.SearchResult r : results) {
            sb.append(String.format("- **%s** (Page %d, *%s*):\n  > %s\n\n",
                    r.document(), r.page(), r.heading(),
                    r.text().replaceAll("\\n", " ").trim()));
        }
        return sb.toString();
    }

    private String buildSystemPrompt(String query, String context) {
        return "You are a specialized enterprise legal and document QA assistant.\n" +
                "Answer the following question STRICTLY and ONLY using the provided document context.\n" +
                "Always cite the source document name, page number, and section heading.\n" +
                "Do not make up facts or extrapolate beyond the provided text.\n\n" +
                "CONTEXT:\n" + context + "\n\n" +
                "QUESTION:\n" + query + "\n\n" +
                "ANSWER:";
    }

    public record Citation(String document, int page, String heading, double similarityScore, String preview) {}

    public record RagAnswer(String query, String answer, List<Citation> citations, int totalContextChunks) {}
}
