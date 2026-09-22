package com.nextgem.smartrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
@ConfigurationProperties(prefix = "rag.pipeline")
public class RagPipelineProperties {

    /**
     * Absolute base directory for pipeline storage
     */
    private String baseDir = "data";

    /**
     * Input directory for raw PDFs
     */
    private String rawPdfDir = "data/raw_pdfs";

    /**
     * Output directory for streamed Markdown documents
     */
    private String stagingMdDir = "data/staging_md";

    /**
     * Output directory for chunked JSON Lines files
     */
    private String chunkedJsonlDir = "data/chunked_jsonl";

    /**
     * Output directory for local vector persistence / scratch
     */
    private String vectorStorageDir = "data/vector_storage";

    /**
     * Concurrency limit (default is CPU cores count)
     */
    private int maxConcurrency = Math.max(2, Runtime.getRuntime().availableProcessors());

    /**
     * Max heap usage threshold (MB) before pausing new ingestion tasks
     */
    private long memorySafetyThresholdMb = 6144; // 6GB baseline

    /**
     * RAG tokenization chunk size (characters)
     */
    private int chunkSize = 1200;

    /**
     * Overlap between consecutive chunks (characters)
     */
    private int chunkOverlap = 150;

    /**
     * RAM capacity in GB for hardware profile (default 8GB)
     */
    private int ramCapacityGb = 8;

    /**
     * Batch size for reading pages in large PDFs to guarantee bounded memory
     */
    private int pageBatchSize = 20;

    /**
     * Large file threshold (MB)
     */
    private long largeFileThresholdMb = 50;

    /**
     * Scratch/temp directory for working files (never modifies originals)
     */
    private String optimizedPdfDir = "data/optimized_pdfs";

    /**
     * ChromaDB REST endpoint (matches docker-compose 8005)
     */
    private String chromaUrl = "http://localhost:8005";

    /**
     * Target collection name in ChromaDB
     */
    private String chromaCollection = "rag_documents";

    // Getters and Setters
    public int getRamCapacityGb() { return ramCapacityGb; }
    public void setRamCapacityGb(int ramCapacityGb) { this.ramCapacityGb = ramCapacityGb; }

    public int getPageBatchSize() { return pageBatchSize; }
    public void setPageBatchSize(int pageBatchSize) { this.pageBatchSize = pageBatchSize; }

    public long getLargeFileThresholdMb() { return largeFileThresholdMb; }
    public void setLargeFileThresholdMb(long largeFileThresholdMb) { this.largeFileThresholdMb = largeFileThresholdMb; }

    public String getOptimizedPdfDir() { return optimizedPdfDir; }
    public void setOptimizedPdfDir(String optimizedPdfDir) { this.optimizedPdfDir = optimizedPdfDir; }
    public Path getOptimizedPdfPath() { return Paths.get(optimizedPdfDir).toAbsolutePath(); }

    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }

    public String getRawPdfDir() { return rawPdfDir; }
    public void setRawPdfDir(String rawPdfDir) { this.rawPdfDir = rawPdfDir; }

    public String getStagingMdDir() { return stagingMdDir; }
    public void setStagingMdDir(String stagingMdDir) { this.stagingMdDir = stagingMdDir; }

    public String getChunkedJsonlDir() { return chunkedJsonlDir; }
    public void setChunkedJsonlDir(String chunkedJsonlDir) { this.chunkedJsonlDir = chunkedJsonlDir; }

    public String getVectorStorageDir() { return vectorStorageDir; }
    public void setVectorStorageDir(String vectorStorageDir) { this.vectorStorageDir = vectorStorageDir; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }

    public long getMemorySafetyThresholdMb() { return memorySafetyThresholdMb; }
    public void setMemorySafetyThresholdMb(long memorySafetyThresholdMb) { this.memorySafetyThresholdMb = memorySafetyThresholdMb; }

    public long getEffectiveMemorySafetyCeilingMb() {
        long jvmMaxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        if (jvmMaxMb > 0) {
            return Math.min(memorySafetyThresholdMb, (long) (jvmMaxMb * 0.85));
        }
        return memorySafetyThresholdMb;
    }

    public int getChunkSize() { return chunkSize; }
    public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }

    public int getChunkOverlap() { return chunkOverlap; }
    public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }

    public String getChromaUrl() { return chromaUrl; }
    public void setChromaUrl(String chromaUrl) { this.chromaUrl = chromaUrl; }

    public String getChromaCollection() { return chromaCollection; }
    public void setChromaCollection(String chromaCollection) { this.chromaCollection = chromaCollection; }

    public Path getRawPdfPath() { return Paths.get(rawPdfDir).toAbsolutePath(); }
    public Path getStagingMdPath() { return Paths.get(stagingMdDir).toAbsolutePath(); }
    public Path getChunkedJsonlPath() { return Paths.get(chunkedJsonlDir).toAbsolutePath(); }
    public Path getVectorStoragePath() { return Paths.get(vectorStorageDir).toAbsolutePath(); }
}
