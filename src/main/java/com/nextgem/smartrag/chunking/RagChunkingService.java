package com.nextgem.smartrag.chunking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgem.smartrag.config.RagPipelineProperties;
import com.nextgem.smartrag.service.DynamicHardwareTuningService;
import com.nextgem.smartrag.service.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Enterprise line-streaming Markdown chunker.
 * Slices Markdown into semantic, page-aware RAG chunks and streams them directly
 * to disk as JSON Lines (.jsonl) without loading entire documents into memory.
 */
@Service
public class RagChunkingService {

    private static final Logger log = LoggerFactory.getLogger(RagChunkingService.class);
    private static final Pattern PAGE_MARKER_PATTERN = Pattern.compile("^##\\s+Page\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING_PATTERN = Pattern.compile("^###?\\s+(.+)$");

    private final RagPipelineProperties properties;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper;
    private final DynamicHardwareTuningService tuningService;
    private final ResourceManager resourceManager;

    private final AtomicInteger totalChunksGenerated = new AtomicInteger(0);

    public RagChunkingService(
            RagPipelineProperties properties,
            @Qualifier("pipelineExecutor") ExecutorService executor,
            ObjectMapper objectMapper,
            DynamicHardwareTuningService tuningService,
            ResourceManager resourceManager
    ) {
        this.properties = properties;
        this.executor = executor;
        this.objectMapper = objectMapper;
        this.tuningService = tuningService;
        this.resourceManager = resourceManager;
    }

    /**
     * Chunks all staged Markdown files in parallel and saves JSONL output.
     */
    public ChunkingSummary chunkAllStagedDocuments() {
        Path stagingDir = properties.getStagingMdPath();
        Path jsonlDir = properties.getChunkedJsonlPath();

        try {
            Files.createDirectories(jsonlDir);
        } catch (IOException e) {
            throw new RuntimeException("Could not create JSONL directory: " + jsonlDir, e);
        }

        totalChunksGenerated.set(0);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        try (Stream<Path> mdStream = Files.list(stagingDir)) {
            mdStream.filter(p -> p.toString().endsWith(".md"))
                    .forEach(mdPath -> {
                        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                            try {
                                tuningService.acquirePermit();
                                resourceManager.checkAndThrottle();
                                chunkSingleMarkdownFile(mdPath, jsonlDir);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                log.warn("[STAGE:CHUNK] Interrupted while chunking: {}", mdPath.getFileName());
                            } finally {
                                tuningService.releasePermit();
                            }
                        }, executor);
                        futures.add(future);
                    });

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            int totalProcessedFiles = futures.size();
            futures.clear();

            // PARTITION 2 MEMORY CLEAR: Reclaim partition heap memory immediately
            resourceManager.forceReclaim();

            log.info("[STAGE:CHUNK] Chunking finished. Processed: {}, Total chunks: {}",
                    totalProcessedFiles, totalChunksGenerated.get());
            return new ChunkingSummary(totalProcessedFiles, totalChunksGenerated.get());
        } catch (IOException e) {
            log.error("[STAGE:CHUNK] Failed listing staging Markdown directory: {}", stagingDir, e);
            return new ChunkingSummary(0, 0);
        }
    }

    /**
     * Streams a single Markdown file line-by-line and writes JSON lines.
     * Guaranteed cleanup on failure to avoid corrupted or partial JSONL.
     */
    public void chunkSingleMarkdownFile(Path mdPath, Path jsonlDir) {
        String baseName = mdPath.getFileName().toString().replaceFirst("\\.md$", "");
        Path targetJsonlPath = jsonlDir.resolve(baseName + ".jsonl");

        int targetChunkSize = properties.getChunkSize();
        int overlap = properties.getChunkOverlap();

        int currentPage = 1;
        String currentHeading = "General";
        StringBuilder buffer = new StringBuilder();
        int chunkIndex = 0;

        try (BufferedReader reader = Files.newBufferedReader(mdPath, StandardCharsets.UTF_8);
             BufferedWriter writer = Files.newBufferedWriter(
                     targetJsonlPath,
                     StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE
             )) {

            String line;
            while ((line = reader.readLine()) != null) {
                // Check for Page boundary
                Matcher pageMatcher = PAGE_MARKER_PATTERN.matcher(line);
                if (pageMatcher.find()) {
                    int newPage = Integer.parseInt(pageMatcher.group(1));
                    // If previous page has content in buffer, flush it to preserve page boundary
                    if (buffer.length() > 0 && !buffer.toString().trim().isEmpty()) {
                        RagChunk chunk = new RagChunk(
                                baseName + "_chunk_" + chunkIndex++,
                                baseName,
                                currentPage,
                                currentHeading,
                                buffer.toString().trim(),
                                buffer.length()
                        );
                        writer.write(objectMapper.writeValueAsString(chunk));
                        writer.newLine();
                        totalChunksGenerated.incrementAndGet();
                        buffer.setLength(0);
                    }
                    currentPage = newPage;
                    continue;
                }

                // Check for Heading boundary
                Matcher headingMatcher = HEADING_PATTERN.matcher(line);
                if (headingMatcher.matches()) {
                    currentHeading = headingMatcher.group(1).trim();
                }

                buffer.append(line).append("\n");

                // When buffer exceeds chunk size, write out chunk
                if (buffer.length() >= targetChunkSize) {
                    RagChunk chunk = new RagChunk(
                            baseName + "_chunk_" + chunkIndex++,
                            baseName,
                            currentPage,
                            currentHeading,
                            buffer.toString().trim(),
                            buffer.length()
                    );

                    writer.write(objectMapper.writeValueAsString(chunk));
                    writer.newLine();
                    totalChunksGenerated.incrementAndGet();

                    // Carry over overlap window with word-boundary awareness
                    int keepChars = Math.min(overlap, buffer.length());
                    int cutIndex = buffer.length() - keepChars;
                    // Find nearest whitespace to avoid splitting words in half
                    while (cutIndex < buffer.length() && !Character.isWhitespace(buffer.charAt(cutIndex))) {
                        cutIndex++;
                    }
                    if (cutIndex >= buffer.length()) {
                        cutIndex = buffer.length() - keepChars;
                    }
                    String overlapText = buffer.substring(cutIndex).trim();
                    buffer.setLength(0);
                    if (!overlapText.isEmpty()) {
                        buffer.append(overlapText).append(" ");
                    }
                }
            }

            // Flush remaining buffer
            if (buffer.length() > 0 && !buffer.toString().trim().isEmpty()) {
                RagChunk chunk = new RagChunk(
                        baseName + "_chunk_" + chunkIndex,
                        baseName,
                        currentPage,
                        currentHeading,
                        buffer.toString().trim(),
                        buffer.length()
                );
                writer.write(objectMapper.writeValueAsString(chunk));
                writer.newLine();
                totalChunksGenerated.incrementAndGet();
            }

            writer.flush();
            log.debug("[DOC:{}] [STAGE:CHUNK] Streamed {} chunks to {}", baseName, chunkIndex + 1, targetJsonlPath.getFileName());

        } catch (Exception e) {
            log.error("[DOC:{}] [STAGE:CHUNK] Error chunking Markdown file: {}", baseName, e.getMessage());
            try {
                Files.deleteIfExists(targetJsonlPath);
            } catch (IOException ignored) {}
        }
    }

    public record RagChunk(
            String chunkId,
            String documentName,
            int pageNumber,
            String heading,
            String content,
            int characterCount
    ) {}

    public record ChunkingSummary(
            int documentsProcessed,
            int totalChunks
    ) {}
}
