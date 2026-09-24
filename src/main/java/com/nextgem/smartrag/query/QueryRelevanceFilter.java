package com.nextgem.smartrag.query;

import com.nextgem.smartrag.vectorstore.ChromaVectorStoreService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Intelligent Query Relevance Filter and Conversational Synthesizer.
 * Ensures:
 * 1. Conversational queries (greetings, pleasantries) receive natural, conversational responses
 *    without querying or dumping document chunks.
 * 2. Real queries receive clean, plain, direct answers without page numbers, boilerplate metadata,
 *    or raw code/script wastage.
 * 3. Boilerplate preambles (> Source:, # Document:) are completely eliminated.
 */
@Component
public class QueryRelevanceFilter {

    private static final double DEFAULT_MIN_SIMILARITY = 0.28;
    private static final Pattern PREAMBLE_PATTERN = Pattern.compile("(?i)^#\\s*Document:.*>\\s*Source:.*>\\s*Total Pages:.*", Pattern.DOTALL);

    private static final Set<String> GREETINGS = Set.of(
            "hi", "hello", "hey", "hola", "namaste", "greetings", "good morning", "good evening", "good afternoon"
    );

    private static final Set<String> PLEASANTRIES = Set.of(
            "thanks", "thank you", "thx", "appreciate it", "great", "ok", "okay", "cool", "bye", "goodbye"
    );

    /**
     * Detects if a query is a greeting, pleasantry, or meta-question.
     */
    public boolean isConversationalQuery(String query) {
        if (query == null || query.isBlank()) return false;
        String clean = query.trim().toLowerCase().replaceAll("[^a-z\\s]", "").replaceAll("\\s+", " ");
        if (GREETINGS.contains(clean) || PLEASANTRIES.contains(clean)) {
            return true;
        }
        if (clean.equals("who are you") || clean.equals("what can you do") || clean.equals("help") || clean.equals("how are you")) {
            return true;
        }
        return false;
    }

    /**
     * Generates a direct, helpful conversational response.
     */
    public String getConversationalResponse(String query) {
        String clean = (query != null) ? query.trim().toLowerCase().replaceAll("[^a-z\\s]", "").replaceAll("\\s+", " ") : "";
        if (GREETINGS.contains(clean)) {
            return "Hello! I am your RAG Assistant. Ask me any question about your documents, guides, or data, and I will provide you with a direct, accurate answer.";
        }
        if (clean.equals("who are you") || clean.equals("what can you do") || clean.equals("help")) {
            return "I am an enterprise RAG assistant. I search across your indexed documents and provide verified, context-grounded answers without unnecessary filler. How can I help you today?";
        }
        if (clean.equals("how are you")) {
            return "I'm running smoothly and ready to help! What question can I answer for you from your documents?";
        }
        if (PLEASANTRIES.contains(clean)) {
            return "You're welcome! Feel free to ask if you have any other questions.";
        }
        return "Hello! How can I assist you with your documents today?";
    }

    /**
     * Determines whether a chunk is merely an extraction header or metadata preamble.
     */
    public boolean isBoilerplateOrPreamble(String text) {
        if (text == null || text.isBlank()) return true;
        String trimmed = text.trim();
        if (trimmed.length() < 240 && (trimmed.startsWith("# Document:") || trimmed.startsWith("> Source:"))) {
            return true;
        }
        if (PREAMBLE_PATTERN.matcher(trimmed).matches()) {
            return true;
        }
        return false;
    }

    /**
     * Strips pipeline metadata artifacts from chunk text before passing to LLM or user.
     */
    public String cleanChunkContent(String text) {
        if (text == null) return "";
        String[] lines = text.split("\\r?\\n");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            // Skip raw pipeline metadata lines
            if (trimmed.startsWith("# Document:") || trimmed.startsWith("> Source:") || trimmed.startsWith("> Total Pages:") || trimmed.startsWith("---") || trimmed.toLowerCase().startsWith("## page ") || trimmed.toLowerCase().startsWith("page ")) {
                continue;
            }
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Filters a list of vector search results, removing boilerplate and low-confidence matches.
     */
    public List<ChromaVectorStoreService.SearchResult> filterRelevantResults(
            List<ChromaVectorStoreService.SearchResult> results,
            String query,
            double minThreshold
    ) {
        if (results == null || results.isEmpty()) return List.of();

        double threshold = (minThreshold > 0) ? minThreshold : DEFAULT_MIN_SIMILARITY;

        List<ChromaVectorStoreService.SearchResult> filtered = new ArrayList<>();
        for (ChromaVectorStoreService.SearchResult res : results) {
            String cleanText = cleanChunkContent(res.text());
            if (isBoilerplateOrPreamble(res.text()) || cleanText.isBlank()) {
                continue;
            }
            // Check if chunk is mostly python code dump when user didn't ask for code
            if (isIrrelevantCodeDump(cleanText, query)) {
                continue;
            }
            if (res.score() >= threshold) {
                filtered.add(new ChromaVectorStoreService.SearchResult(
                        res.id(),
                        res.document(),
                        res.page(),
                        res.heading(),
                        cleanText,
                        res.score(),
                        res.sources()
                ));
            }
        }

        // If strict filtering removed everything, check if any non-code, non-boilerplate chunk exists
        if (filtered.isEmpty() && !results.isEmpty()) {
            for (ChromaVectorStoreService.SearchResult res : results) {
                String clean = cleanChunkContent(res.text());
                if (!isBoilerplateOrPreamble(res.text()) && !clean.isBlank() && !isIrrelevantCodeDump(clean, query)) {
                    filtered.add(new ChromaVectorStoreService.SearchResult(
                            res.id(), res.document(), res.page(), res.heading(), clean, res.score(), res.sources()
                    ));
                    break;
                }
            }
        }

        return filtered;
    }

    /**
     * Synthesizes a clean, plain, direct answer to the user's query:
     * - NO page numbers or document labels in the answer body (citations provide them cleanly separately)
     * - NO raw code dumps or scripts
     * - NO "### Extractive Retrieval Summary" header noise
     */
    public String synthesizeExtractiveAnswer(
            String query,
            List<ChromaVectorStoreService.SearchResult> cleanResults
    ) {
        if (cleanResults == null || cleanResults.isEmpty()) {
            return "No verified document sections matched your query with sufficient relevance.";
        }

        Set<String> queryKeywords = extractKeywords(query);
        List<String> keyPoints = new ArrayList<>();

        for (ChromaVectorStoreService.SearchResult res : cleanResults) {
            List<String> salient = extractSalientSentences(res.text(), queryKeywords);
            for (String s : salient) {
                if (!keyPoints.contains(s)) {
                    keyPoints.add(s);
                }
            }
            if (keyPoints.size() >= 4) break;
        }

        // If keyword matching found clean salient sentences, synthesize direct prose
        if (!keyPoints.isEmpty()) {
            if (keyPoints.size() == 1) {
                return keyPoints.get(0);
            }
            StringBuilder sb = new StringBuilder();
            for (String point : keyPoints) {
                sb.append("• ").append(point).append("\n");
            }
            return sb.toString().trim();
        }

        // Fallback: If no direct sentence matched, provide clean summary text of the first non-code chunk
        for (ChromaVectorStoreService.SearchResult res : cleanResults) {
            String text = res.text().replaceAll("\\r?\\n+", " ").trim();
            if (isIrrelevantCodeDump(text, query)) {
                continue;
            }
            if (text.length() > 320) {
                int dot = text.indexOf('.', 200);
                if (dot != -1 && dot < 380) {
                    text = text.substring(0, dot + 1);
                } else {
                    text = text.substring(0, 320) + "…";
                }
            }
            return text;
        }

        return "Relevant sections were found in your documents, but they contain technical code or listings. Please specify 'code' if you would like to inspect the implementation directly.";
    }

    public boolean isIrrelevantCodeDump(String text, String query) {
        if (text == null || text.isBlank()) return false;
        String lowerQuery = (query != null) ? query.toLowerCase() : "";
        boolean asksForCode = lowerQuery.contains("code") || lowerQuery.contains("script") ||
                lowerQuery.contains("python") || lowerQuery.contains("function") ||
                lowerQuery.contains("implement") || lowerQuery.contains("program");

        if (asksForCode) return false;

        String lowerText = text.toLowerCase();
        int matches = 0;
        String[] codeMarkers = {
                "def ", "return ", "import ", "try:", "except ", "print(", "class ",
                "os.path", "with open", "while ", "writer.", "reader.", "uuid.", "len(",
                "range(", ".append(", "elif ", "else:", "==", "!=", "lambda ", "self.",
                "status_log", "chunk_path"
        };
        for (String marker : codeMarkers) {
            if (lowerText.contains(marker)) {
                matches++;
            }
        }

        return matches >= 2;
    }

    private List<String> extractSalientSentences(String text, Set<String> queryKeywords) {
        List<String> salient = new ArrayList<>();
        String[] sentences = text.split("(?<=[.!?\\n])\\s+");

        for (String sentence : sentences) {
            String trimmed = sentence.replaceAll("\\s+", " ").trim();
            // Skip code lines, page numbers or noise
            if (trimmed.length() < 25 || trimmed.startsWith("#") || trimmed.startsWith("import ") ||
                    trimmed.startsWith("try:") || trimmed.startsWith("print(") || trimmed.startsWith("except") ||
                    trimmed.toLowerCase().startsWith("page ") || trimmed.toLowerCase().startsWith("## page ") ||
                    trimmed.contains(" = ") || trimmed.startsWith("def ") || trimmed.startsWith("return ") ||
                    trimmed.contains("os.path") || trimmed.contains("uuid.")) {
                continue;
            }
            String lower = trimmed.toLowerCase();
            long matches = queryKeywords.stream().filter(lower::contains).count();
            if (matches > 0) {
                salient.add(trimmed);
                if (salient.size() >= 2) break;
            }
        }
        return salient;
    }

    private Set<String> extractKeywords(String query) {
        Set<String> stopWords = Set.of(
                "what", "is", "the", "a", "an", "and", "or", "how", "to", "in", "on", "for",
                "with", "about", "tell", "me", "give", "can", "you", "does", "do", "explain"
        );
        return Arrays.stream(query.toLowerCase().split("[^a-z0-9]+"))
                .filter(w -> w.length() > 2 && !stopWords.contains(w))
                .collect(Collectors.toSet());
    }
}
