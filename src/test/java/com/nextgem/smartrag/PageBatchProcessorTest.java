package com.nextgem.smartrag;

import com.nextgem.smartrag.parser.PageBatchProcessor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class PageBatchProcessorTest {

    @Autowired
    private PageBatchProcessor pageBatchProcessor;

    @Test
    public void testMultiPageBatchStreamingPreservesOriginalFile() throws IOException {
        Path tempDir = Files.createTempDirectory("rag_page_batch_test");
        Path testPdf = tempDir.resolve("multi_page_doc.pdf");
        Path outputMd = tempDir.resolve("multi_page_doc.md");

        // 1. Generate a 10-page synthetic PDF
        int totalPagesToCreate = 10;
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (int p = 1; p <= totalPagesToCreate; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                    stream.beginText();
                    stream.setFont(font, 14);
                    stream.newLineAtOffset(50, 700);
                    stream.showText("CONFIDENTIAL LEGAL REPORT - SECTION " + p);
                    stream.newLineAtOffset(0, -30);
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.showText("This is page content for page number " + p + " with important data.");
                    stream.endText();
                }
            }
            doc.save(testPdf.toFile());
        }

        assertTrue(Files.exists(testPdf));
        long originalSizeBytes = Files.size(testPdf);
        long originalLastModified = Files.getLastModifiedTime(testPdf).toMillis();

        // 2. Process in page batches of 3 pages (will take 4 batches: 3+3+3+1)
        AtomicLong extractedCounter = new AtomicLong(0);
        PageBatchProcessor.BatchProcessResult result = pageBatchProcessor.processDocumentInBatches(
                testPdf,
                outputMd,
                3,
                extractedCounter
        );

        // 3. Verify extraction success
        assertTrue(result.success(), "Page batch extraction should succeed");
        assertEquals(totalPagesToCreate, result.totalPages(), "Extracted pages should match total pages");
        assertEquals(totalPagesToCreate, extractedCounter.get(), "Metric counter should equal total pages");
        assertTrue(Files.exists(outputMd), "Output markdown file should exist");

        // 4. Verify ZERO data loss or file mutation (original PDF is 100% untouched)
        assertEquals(originalSizeBytes, Files.size(testPdf), "Original PDF size must NOT change");
        assertEquals(originalLastModified, Files.getLastModifiedTime(testPdf).toMillis(), "Original PDF must NOT be modified");

        // 5. Verify extracted markdown contains data from first and last pages
        String mdContent = Files.readString(outputMd);
        assertTrue(mdContent.contains("SECTION 1"), "Must contain page 1 heading");
        assertTrue(mdContent.contains("SECTION 10"), "Must contain page 10 heading");
        assertTrue(mdContent.contains("page content for page number 5"), "Must contain page 5 content");

        // Cleanup
        Files.deleteIfExists(testPdf);
        Files.deleteIfExists(outputMd);
        Files.deleteIfExists(tempDir);
    }
}
