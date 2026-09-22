package com.nextgem.smartrag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgem.smartrag.config.RagPipelineProperties;
import com.nextgem.smartrag.vectorstore.ChromaVectorStoreService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class ChunkDeduplicationTest {

    @Autowired
    private ChromaVectorStoreService vectorStoreService;

    @Autowired
    private RagPipelineProperties properties;

    @Autowired
    private ObjectMapper objectMapper;

    private Path tempJsonlDir;
    private Path tempVectorDir;

    @BeforeEach
    void setUp() throws IOException {
        tempJsonlDir = Files.createTempDirectory("test_jsonl_");
        tempVectorDir = Files.createTempDirectory("test_vectors_");
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanDir(tempJsonlDir);
        cleanDir(tempVectorDir);
    }

    private void cleanDir(Path dir) throws IOException {
        if (dir != null && Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void testComputeContentHashIsDeterministicAndNormalized() {
        String text1 = "All warranties are hereby disclaimed to the fullest extent permitted by law.";
        String text2 = "  all   warranties ARE hereby  disclaimed to the fullest extent permitted by law. \n\n";
        String text3 = "Different text completely.";

        String hash1 = ChromaVectorStoreService.computeContentHash(text1);
        String hash2 = ChromaVectorStoreService.computeContentHash(text2);
        String hash3 = ChromaVectorStoreService.computeContentHash(text3);

        assertNotNull(hash1);
        assertTrue(hash1.startsWith("chk_"));
        assertEquals(hash1, hash2, "Content hashes should match after case and whitespace normalization");
        assertNotEquals(hash1, hash3, "Different content should produce different hashes");
    }

    @Test
    void testContentAddressedDeduplicationSkipsRedundantVectorsAndTracksSources() throws IOException {
        // Create 2 JSONL files with an identical chunk paragraph
        String sharedContent = "Standard Enterprise Liability Clause: Neither party shall be liable for indirect damages.";

        Path file1 = tempJsonlDir.resolve("Contract_A.jsonl");
        Path file2 = tempJsonlDir.resolve("Contract_B.jsonl");

        writeChunk(file1, "Contract_A", 1, "Section 1", "Unique intro for contract A.");
        writeChunk(file1, "Contract_A", 2, "Liability", sharedContent);

        writeChunk(file2, "Contract_B", 1, "Overview", "Unique overview for contract B.");
        writeChunk(file2, "Contract_B", 3, "Terms", sharedContent); // EXACT SAME CHUNK as in Contract_A

        // Ingest file1
        vectorStoreService.ingestSingleJsonl(file1, tempVectorDir);

        // Ingest file2
        vectorStoreService.ingestSingleJsonl(file2, tempVectorDir);

        // Vector store status should report deduplication active
        Map<String, Object> status = vectorStoreService.getVectorDbStatus();
        assertTrue((Boolean) status.get("chunkDeduplicationActive"));
        int deduplicated = (Integer) status.get("totalChunksDeduplicated");
        assertTrue(deduplicated >= 1, "At least 1 chunk should be deduplicated across contracts");

        // Verify the generated vector files exist
        assertTrue(Files.exists(tempVectorDir.resolve("Contract_A.vectors.jsonl")));
        assertTrue(Files.exists(tempVectorDir.resolve("Contract_B.vectors.jsonl")));
    }

    private void writeChunk(Path jsonlFile, String docName, int page, String heading, String content) throws IOException {
        Map<String, Object> chunk = new HashMap<>();
        chunk.put("chunkId", docName + "_p" + page + "_c1");
        chunk.put("documentName", docName);
        chunk.put("pageNumber", page);
        chunk.put("heading", heading);
        chunk.put("content", content);

        try (BufferedWriter writer = Files.newBufferedWriter(
                jsonlFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            writer.write(objectMapper.writeValueAsString(chunk));
            writer.newLine();
        }
    }
}
