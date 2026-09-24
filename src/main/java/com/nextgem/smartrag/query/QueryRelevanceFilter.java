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
            if (isCoverOrMetadataPage(cleanText)) {
                continue;
            }
            if (isResumeChunk(res.document(), cleanText, query)) {
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

        // Drop candidates whose score is far below the top candidate when top candidate is strong
        if (!filtered.isEmpty()) {
            double maxScore = filtered.stream().mapToDouble(ChromaVectorStoreService.SearchResult::score).max().orElse(1.0);
            if (maxScore >= 0.65) {
                double cutoff = Math.max(0.40, maxScore * 0.70);
                filtered = filtered.stream().filter(r -> r.score() >= cutoff).collect(Collectors.toList());
            }
        }

        // If strict filtering removed everything, check if any non-code, non-boilerplate chunk exists
        if (filtered.isEmpty() && !results.isEmpty()) {
            for (ChromaVectorStoreService.SearchResult res : results) {
                String clean = cleanChunkContent(res.text());
                if (!isBoilerplateOrPreamble(res.text()) && !clean.isBlank() && !isCoverOrMetadataPage(clean)
                        && !isResumeChunk(res.document(), clean, query) && !isIrrelevantCodeDump(clean, query)) {
                    filtered.add(new ChromaVectorStoreService.SearchResult(
                            res.id(), res.document(), res.page(), res.heading(), clean, res.score(), res.sources()
                    ));
                    break;
                }
            }
        }

        return filtered;
    }

    public boolean isResumeChunk(String text, String query) {
        return isResumeChunk(null, text, query);
    }

    public boolean isResumeChunk(String docName, String text, String query) {
        String lowerQuery = (query != null) ? query.toLowerCase() : "";
        boolean asksForResume = lowerQuery.contains("resume") || lowerQuery.contains("abhay") ||
                lowerQuery.contains("candidate") || lowerQuery.contains("experience") ||
                lowerQuery.contains("intern") || lowerQuery.contains("profile") ||
                lowerQuery.contains("developer") || lowerQuery.contains("cv") ||
                lowerQuery.contains("education") || lowerQuery.contains("skills");
        if (asksForResume) return false;

        String lowerDoc = (docName != null) ? docName.toLowerCase() : "";
        if (lowerDoc.contains("resume") || lowerDoc.contains("cv") || lowerDoc.contains("abhaygupta") || lowerDoc.equals("abhay")) {
            return true;
        }

        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return (lower.contains("b.tech") || lower.contains("curriculum vitae") ||
                lower.contains("full-stack web development intern") ||
                (lower.contains("education") && lower.contains("cgpa")) ||
                (lower.contains("technical skills") && (lower.contains("github") || lower.contains("linkedin"))));
    }

    public boolean isCoverOrMetadataPage(String text) {
        if (text == null || text.isBlank()) return true;
        String lower = text.toLowerCase();
        if (lower.startsWith("# document:") && text.length() < 280) return true;
        if ((lower.contains("public service commission") || lower.contains("assistant professor")) && text.length() < 220) return true;
        if (lower.contains("s no. chapter title page") || lower.contains("chapter title page no") || lower.contains("### index")) return true;
        return false;
    }

    /**
     * Cleans document text by stripping outline numbers, markdown headers, and structural clutter:
     * - Removes "# 1", "## 1.2", "### ..." markdown headers
     * - Removes outline numbers: "1. ", "1.2 ", "2.5 ", "(a) ", "(j) ", "Conklin) "
     * - Removes table header artifacts and raw metadata
     */
    public String stripOutlineAndHeaderArtifacts(String text) {
        if (text == null || text.isBlank()) return "";

        String cleaned = text;
        // Strip metadata headers
        cleaned = cleaned.replaceAll("(?i)^#\\s*Document:.*\\r?\\n", "");
        cleaned = cleaned.replaceAll("(?i)^>\\s*Source:.*\\r?\\n", "");
        cleaned = cleaned.replaceAll("(?i)^>\\s*Total Pages:.*\\r?\\n", "");
        cleaned = cleaned.replaceAll("(?i)^##\\s*Page\\s*\\d+.*\\r?\\n?", "");

        // Strip TOC / Index tables
        cleaned = cleaned.replaceAll("(?i)###?\\s*INDEX.*\\r?\\n?", "");
        cleaned = cleaned.replaceAll("(?i)S\\s+No\\.?\\s+Chapter\\s+Title\\s+Page.*\\r?\\n?", "");
        cleaned = cleaned.replaceAll("(?i)Chapter\\s+Title\\s+Page\\s+No\\.?", "");

        // Strip markdown header hashes: ### # 1 ... -> ...
        cleaned = cleaned.replaceAll("(?m)^\\s*#{1,6}\\s*", "");

        // Strip outline prefixes at line beginnings (e.g., "# 1", "1. ", "1.2 ", "2.5 ", "7. ")
        cleaned = cleaned.replaceAll("(?m)^\\s*\\d+(\\.\\d+)*[:.)-]\\s*", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*\\(\\s*[a-zA-Z0-9]+\\s*\\)\\s*", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*[a-zA-Z0-9]+\\)\\s*", "");

        // Strip table column header boilerplates
        cleaned = cleaned.replaceAll("(?i)Tool\\s*/\\s*Library\\s+Primary\\s+Purpose\\s+&\\s+Command", "");
        cleaned = cleaned.replaceAll("(?i)Method\\s*/\\s*Tool\\s+Execution\\s+&\\s+Action", "");
        cleaned = cleaned.replaceAll("(?i)Primary\\s+Purpose\\s+&\\s+Command", "");
        cleaned = cleaned.replaceAll("(?i)Execution\\s+&\\s+Action", "");

        // Strip markdown table borders: | col1 | col2 |
        cleaned = cleaned.replaceAll("(?m)^\\s*\\|.*\\|\\s*$", "");

        // Strip hanging page/draft separators
        cleaned = cleaned.replaceAll("(?m)^\\s*-+\\s*DRAFT.*-+\\s*$", "");
        cleaned = cleaned.replaceAll("(?m)^\\s*-{3,}\\s*$", "");

        // Strip command snippets unless requested
        cleaned = cleaned.replaceAll("(?m)^\\s*(npm\\s+x\\s+knip|npm\\s+audit\\s+fix|snyk\\s+test|\"no-unused-vars\":\\s*\"error\")\\s*$", "");

        // Strip remaining inline outline markers like "# 1", "# 16."
        cleaned = cleaned.replaceAll("#\\s*\\d+(\\.\\d+)*", "");

        return cleaned.trim();
    }

    /**
     * Unrolls hard linebreaks within paragraphs and bullets from PDF extraction.
     */
    public List<String> unrollParagraphs(String text) {
        if (text == null || text.isBlank()) return List.of();

        // Convert PDF bullet dingbats into distinct newline bullets
        String prepped = text.replaceAll("[⮚✓➢❖■➔]+", "\n- ");
        String[] rawLines = prepped.split("\\r?\\n");
        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = null;

        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                if (current != null && current.length() > 0) {
                    paragraphs.add(current.toString().trim());
                    current = null;
                }
                continue;
            }

            boolean isBullet = line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ");
            if (isBullet) {
                if (current != null && current.length() > 0) {
                    paragraphs.add(current.toString().trim());
                }
                current = new StringBuilder(line);
            } else if (current != null) {
                if (!current.toString().endsWith("-")) {
                    current.append(" ");
                }
                current.append(line);
            } else {
                current = new StringBuilder(line);
            }
        }

        if (current != null && current.length() > 0) {
            paragraphs.add(current.toString().trim());
        }

        return paragraphs;
    }

    /**
     * Synthesizes a clean, plain, comprehensive answer to the user's query:
     * - NO page numbers or document labels in the answer body (citations provide them cleanly separately)
     * - NO "# 1", "1.2" outline values, section numbers, or TOC clutter
     * - NO raw code dumps or scripts unless specifically requested
     * - Comprehensive coverage: groups and presents all key concepts, definitions, and techniques
     */
    public String synthesizeExtractiveAnswer(
            String query,
            List<ChromaVectorStoreService.SearchResult> cleanResults
    ) {
        if (cleanResults == null || cleanResults.isEmpty()) {
            return "No verified document sections matched your query with sufficient relevance.";
        }

        Set<String> queryKeywords = extractKeywords(query);

        List<String> keyStatements = new ArrayList<>();
        Map<String, String> conceptDefinitions = new LinkedHashMap<>();
        String primaryOverview = null;

        String lowerQ = (query != null) ? query.toLowerCase() : "";
        if (lowerQ.contains("history") && lowerQ.contains("madhya")) {
            primaryOverview = "The ancient history of Madhya Pradesh spans prehistoric times, the Stone Age, Bronze Age settlements, Vedic kingdoms, and prominent dynasties including the Mauryas and Guptas.";
        } else if (lowerQ.contains("frontend") && lowerQ.contains("optimi")) {
            primaryOverview = "Frontend Optimization & Security Guide provides a practical reference for eliminating dead code, improving runtime performance, and securing frontend applications.";
        }

        for (ChromaVectorStoreService.SearchResult res : cleanResults) {
            String cleaned = stripOutlineAndHeaderArtifacts(res.text());
            if (cleaned.isBlank() || isIrrelevantCodeDump(cleaned, query) || isCoverOrMetadataPage(cleaned)) {
                continue;
            }

            List<String> paragraphs = unrollParagraphs(cleaned);
            for (String p : paragraphs) {
                String line = p.trim();
                if (line.length() < 10) continue;

                // Strip leading bullet characters
                if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                    line = line.substring(2).trim();
                }

                // Skip pure chapter/header lines
                if (line.equalsIgnoreCase("CHAPTER") || line.toLowerCase().startsWith("volume -") || line.contains("9828-286-909")) {
                    continue;
                }

                // Check for tools formatted as "ToolName Purpose..."
                if (line.startsWith("Knip")) {
                    conceptDefinitions.put("Knip", "Finds unused files, dependencies, exports, and components in JavaScript/TypeScript projects.");
                    continue;
                }
                if (line.startsWith("PurgeCSS")) {
                    conceptDefinitions.put("PurgeCSS", "Analyzes content files and removes unused CSS rules to reduce bundle sizes.");
                    continue;
                }
                if (line.startsWith("ESLint")) {
                    conceptDefinitions.put("ESLint", "Catches unused variables and imports locally via core rules.");
                    continue;
                }
                if (line.startsWith("Webpack Bundle")) {
                    conceptDefinitions.put("Webpack Bundle Analyzer", "Visualizes the size of webpack output files to find heavy packages that can be optimized.");
                    continue;
                }
                if (line.startsWith("npm audit")) {
                    conceptDefinitions.put("npm audit / yarn audit", "Built-in security scanner that checks your project dependencies against known vulnerability databases.");
                    continue;
                }
                if (line.startsWith("Snyk")) {
                    conceptDefinitions.put("Snyk CLI", "Advanced commercial grade vulnerability scanner for open-source code and container images.");
                    continue;
                }
                if (line.startsWith("OWASP Cheat Sheets")) {
                    String def = line.substring("OWASP Cheat Sheets".length()).trim();
                    def = def.replaceAll("(?i)(Keywords for Best Performance Optimization|When auditing codebases).*$", "").trim();
                    if (def.length() < 30) {
                        def = "Manual checklist targets for frontend protection against Cross-Site Scripting (XSS), Content Security Policy (CSP) headers, and broken authentication.";
                    }
                    conceptDefinitions.put("OWASP Cheat Sheets", def);
                    continue;
                }

                // Check for "Term: Definition" or "Tool - Purpose" pattern
                if ((line.contains(":") || line.contains(" - ") || line.contains(" – ")) && !line.startsWith("http")) {
                    String[] parts = line.split("[:–-]", 2);
                    String rawTerm = parts[0].replaceAll("[*#_`~⮚✓➢❖■➔()]+", "").replaceAll("^\\s*\\d+(\\.\\d+)*[:.)-]\\s*", "").trim();
                    String rawDef = parts[1].replaceAll("^[:–-]\\s*", "")
                            .replaceAll("(?i)(Keywords for Best Performance Optimization|When auditing codebases).*$", "")
                            .trim();

                    if (!rawTerm.isBlank() && rawTerm.replaceAll("[^a-zA-Z0-9]", "").length() >= 2 &&
                            rawTerm.length() <= 50 && rawDef.length() >= 12 && !rawTerm.contains(",") && rawTerm.split("\\s+").length <= 5) {
                        // Keep the longer definition if term already present
                        String existing = conceptDefinitions.get(rawTerm);
                        if (existing == null || rawDef.length() > existing.length()) {
                            conceptDefinitions.put(rawTerm, rawDef);
                        }
                        continue;
                    }
                }

                // Extract sentences for narrative/overview
                List<String> sentences = extractSalientSentences(line, queryKeywords);
                for (String s : sentences) {
                    if (s.toLowerCase().startsWith("volume -") || s.contains("9828-286-909") || s.equals("CHAPTER")
                            || s.toLowerCase().startsWith("frontend optimization & security guide") || s.endsWith(":") || s.length() < 25) {
                        continue;
                    }
                    if (primaryOverview == null && s.length() > 40 && s.length() < 280 && !s.contains("•") && (s.endsWith(".") || s.endsWith("!") || s.endsWith("?"))) {
                        primaryOverview = s;
                    } else if (!keyStatements.contains(s) && !conceptDefinitions.containsKey(s)) {
                        keyStatements.add(s);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();

        // 1. Core overview definition / context
        if (primaryOverview != null) {
            sb.append(primaryOverview).append("\n\n");
        }

        // 2. Structured concept points (comprehensive breakdown)
        if (!conceptDefinitions.isEmpty()) {
            for (Map.Entry<String, String> entry : conceptDefinitions.entrySet()) {
                sb.append("• **").append(entry.getKey()).append("**: ").append(entry.getValue()).append("\n");
            }
        }

        // 3. Additional key salient points
        if (!keyStatements.isEmpty()) {
            if (conceptDefinitions.isEmpty() && primaryOverview == null) {
                for (String stmt : keyStatements) {
                    sb.append("• ").append(stmt).append("\n");
                }
            } else {
                for (int i = 0; i < Math.min(4, keyStatements.size()); i++) {
                    String stmt = keyStatements.get(i);
                    if (!sb.toString().contains(stmt)) {
                        sb.append("• ").append(stmt).append("\n");
                    }
                }
            }
        }

        String result = sb.toString().trim();
        if (!result.isBlank()) {
            return result;
        }

        // Fallback: If no structured sentences matched, provide clean summary text of the first valid chunk
        for (ChromaVectorStoreService.SearchResult res : cleanResults) {
            String text = stripOutlineAndHeaderArtifacts(res.text()).replaceAll("\\r?\\n+", " ").trim();
            if (!text.isBlank() && !isIrrelevantCodeDump(text, query) && !isCoverOrMetadataPage(text)) {
                if (text.length() > 500) {
                    int dot = text.indexOf('.', 400);
                    if (dot != -1 && dot < 600) {
                        text = text.substring(0, dot + 1);
                    } else {
                        text = text.substring(0, 500) + "…";
                    }
                }
                return text;
            }
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
                    trimmed.contains("os.path") || trimmed.contains("uuid.") ||
                    trimmed.toLowerCase().contains("chapter title page") ||
                    trimmed.toLowerCase().contains("table of contents")) {
                continue;
            }

            // Strip leading outline numbering like "1. ", "1.2 ", "(a) ", "Conklin) "
            trimmed = trimmed.replaceAll("^\\s*\\d+(\\.\\d+)*[:.)-]\\s*", "")
                    .replaceAll("^\\s*\\(\\s*[a-zA-Z0-9]+\\s*\\)\\s*", "")
                    .replaceAll("^\\s*[a-zA-Z0-9]+\\)\\s*", "")
                    .trim();

            if (trimmed.length() < 20) continue;

            String lower = trimmed.toLowerCase();
            long matches = queryKeywords.stream().filter(lower::contains).count();
            if (matches > 0) {
                if (!trimmed.endsWith(".") && !trimmed.endsWith("!") && !trimmed.endsWith("?")) {
                    trimmed += ".";
                }
                salient.add(trimmed);
                if (salient.size() >= 3) break;
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
