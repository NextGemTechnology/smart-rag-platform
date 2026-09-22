package com.nextgem.smartrag.parser;

import com.nextgem.smartrag.service.ResourceManager;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enterprise Page-Batch Streaming Processor for Apache PDFBox.
 * Processes PDF documents in bounded page batches (e.g. 20 pages) to ensure
 * constant, predictable memory overhead regardless of total page count or file size.
 *
 * GUARANTEES:
 * 1. Read-Only: NEVER alters, compresses, or overwrites original PDF files.
 * 2. Bounded Heap: Buffers are flushed to disk after each page batch.
 * 3. Backpressure Aware: Checks ResourceManager state between page batches.
 */
@Component
public class PageBatchProcessor {

    private static final Logger log = LoggerFactory.getLogger(PageBatchProcessor.class);

    private final ResourceManager resourceManager;

    public PageBatchProcessor(ResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    public record BatchProcessResult(
            int totalPages,
            long executionTimeMs,
            boolean success,
            String errorMessage
    ) {}

    /**
     * Streams an entire PDF into a staged Markdown file in bounded page batches.
     *
     * @param pdfPath Source PDF file path (read-only)
     * @param targetMdPath Destination markdown path
     * @param batchSize Number of pages to process before flushing and checking backpressure
     * @param totalPagesExtractedCounter Global metric counter to increment per extracted page
     * @return BatchProcessResult summarizing the execution
     */
    public BatchProcessResult processDocumentInBatches(
            Path pdfPath,
            Path targetMdPath,
            int batchSize,
            AtomicLong totalPagesExtractedCounter
    ) {
        long startTime = System.currentTimeMillis();
        File pdfFile = pdfPath.toFile();
        int safeBatchSize = Math.max(5, batchSize);

        PDDocument document = null;
        try {
            // Zero-heap stream cache prevents loading raw PDF bytes into JVM heap
            document = Loader.loadPDF(pdfFile, IOUtils.createTempFileOnlyStreamCache());

            AccessPermission permission = document.getCurrentAccessPermission();
            if (permission != null && !permission.canExtractContent()) {
                log.warn("[PAGE-BATCH] PDF is encrypted/restricted without extraction permissions: {}", pdfPath.getFileName());
                return new BatchProcessResult(0, System.currentTimeMillis() - startTime, false, "Document is encrypted or restricted");
            }

            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                log.warn("[PAGE-BATCH] PDF has 0 pages: {}", pdfPath.getFileName());
                return new BatchProcessResult(0, System.currentTimeMillis() - startTime, false, "Document contains 0 pages");
            }

            String baseName = getBaseName(pdfPath.getFileName().toString());
            MarkdownStructuralPdfStripper stripper = new MarkdownStructuralPdfStripper();

            // Direct OS-level zero-copy write-through buffer
            try (BufferedWriter writer = Files.newBufferedWriter(
                    targetMdPath,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                // Header metadata
                writer.write("# Document: " + baseName);
                writer.newLine();
                writer.write("> Source: " + pdfPath.toAbsolutePath());
                writer.newLine();
                writer.write("> Total Pages: " + totalPages);
                writer.newLine();
                writer.newLine();

                // Process in bounded page windows
                for (int startPage = 1; startPage <= totalPages; startPage += safeBatchSize) {
                    int endPage = Math.min(startPage + safeBatchSize - 1, totalPages);

                    for (int page = startPage; page <= endPage; page++) {
                        stripper.streamPageAsMarkdown(document, page, writer);
                        if (totalPagesExtractedCounter != null) {
                            totalPagesExtractedCounter.incrementAndGet();
                        }
                    }

                    // Flush OS buffer to disk to free page string memory
                    writer.flush();

                    // Check resource state and throttle if under memory pressure
                    resourceManager.checkAndThrottle();
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            return new BatchProcessResult(totalPages, elapsed, true, null);

        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("[PAGE-BATCH] Error processing PDF [{}]: {}", pdfPath.getFileName(), ex.getMessage());
            try {
                Files.deleteIfExists(targetMdPath);
            } catch (IOException ignored) {}
            return new BatchProcessResult(0, elapsed, false, ex.getMessage());
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    log.trace("[PAGE-BATCH] Error closing PDDocument: {}", e.getMessage());
                }
            }
        }
    }

    private String getBaseName(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return (dotIndex == -1) ? filename : filename.substring(0, dotIndex);
    }
}
