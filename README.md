# Enterprise High-Throughput Cross-Platform RAG Ingestion Pipeline

A production-grade, memory-bounded, cross-platform Spring Boot RAG (Retrieval-Augmented Generation) ingestion and question-answering system. Engineered for high-throughput batch processing of large PDF documents across **macOS (Apple Silicon Metal)**, **Windows**, and **Linux** under strict hardware constraints (8–16 CPU cores, 8–32 GB RAM) with zero-heap buffer overflow, content-addressed chunk deduplication, and a live Neomorphic telemetry studio.

---

## 🛠️ Technology Stack

| Layer | Technology | Version / Specification | Purpose |
|-------|------------|-------------------------|---------|
| **Core Framework** | Java / OpenJDK | 17 LTS (Compatible with 21+) | Modern LTS runtime with G1GC memory optimization |
| **Application Framework** | Spring Boot | 3.3.4 | REST API, lifecycle management, thread pooling, JPA |
| **PDF Parsing Engine** | Apache PDFBox | 3.0.3 | High-performance PDF parser with zero-heap scratch buffering |
| **Relational Database** | H2 Database Engine | Embedded (`./data/pipeline_db`) | Audit trail, document job tracking, resume state, and file hashes |
| **Vector Database** | ChromaDB | v0.5+ (`http://localhost:8005`) | Vector embeddings store with auto-fallback to high-speed disk JSONL |
| **Embedding Model** | Deterministic Ingestion Vectorizer | 384-dimensional vector space | Fast, reproducible semantic embedding engine |
| **LLM Providers** | Google Gemini / Ollama / Extractive | Multi-tier failover | Answer generation with multi-source document citations |
| **Frontend UI** | HTML5, CSS3, Vanilla JS | SPA (Neomorphism + Ocean Breeze) | Real-time CPU/RAM telemetry, manual upload/folder selector, Q&A studio |
| **Build & Orchestration** | Apache Maven & Docker Compose | Maven 3.9+ / Docker Compose v2 | Dependency management, testing, containerized ChromaDB |

---

## 🏗️ Architecture & How It Works

```
                     data/raw_pdfs/*.pdf (Original files untouched)
                                    │
                                    ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ PARTITION 1: Streaming PDF Extraction (PdfParallelParserService)          │
│ • OS-Aware Worker Pool (8-16 threads bounded by Semaphore)                │
│ • Apache PDFBox TempFileOnlyStreamCache (zero-copy scratch file buffer)    │
│ • Memory-mapped I/O, page-by-page streaming, immediate flush to disk      │
│ • Strict RAM cleanup after Partition 1 completes                          │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ writes structural markdown
                          data/staging_md/*.md
                                    │
                                    ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ PARTITION 2: Semantic Chunking Engine (RagChunkingService)                │
│ • Page-boundary-aware chunker (Default: 1200 characters, 150 overlap)     │
│ • Generates deterministic content fingerprint (SHA-256) per chunk         │
│ • Preserves document page numbers and heading hierarchy                  │
│ • Strict RAM cleanup after Partition 2 completes                          │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ writes line-delimited records
                       data/chunked_jsonl/*.jsonl
                                    │
                                    ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ PARTITION 3: Vector Store & Deduplication (ChromaVectorStoreService)       │
│ • Content-Addressed Chunk Deduplication: Identical text/tokens are        │
│   fingerprinted — embedding compute is reused, duplicate vectors omitted  │
│ • Multi-Source Citation tracker: tracks document & page references        │
│ • Upserts unique vectors to ChromaDB (or streaming local disk fallback)   │
│ • Strict RAM cleanup after Partition 3 completes                          │
└───────────────────────────────────────────────────────────────────────────┘
                                    │
            ┌───────────────────────┴───────────────────────┐
            ▼                                               ▼
   ChromaDB (port 8005)                     data/vector_storage/*.vectors.jsonl
            │
            ▼
┌───────────────────────────────────────────────────────────────────────────┐
│ RETRIEVAL & SYNTHESIS (RagGenerationService)                              │
│ • Cosine similarity top-K search over indexed vectors                     │
│ • Multi-tier LLM Answer Synthesis:                                       │
│     1. Google Gemini API (if RAG_LLM_GEMINI_API_KEY set)                  │
│     2. Local Ollama (llama3.2 on http://localhost:11434)                  │
│     3. Deterministic Extractive Fallback (guaranteed response)            │
│ • Returns comprehensive answers with exact document & page citations      │
└───────────────────────────────────────────────────────────────────────────┘
```

---

## 🌟 Key Innovations & Engineering Highlights

### 1. Zero-Heap PDF Ingestion & Partition RAM Isolation
- **Non-destructive Processing**: Original PDFs in `data/raw_pdfs/` are strictly read-only and never modified or compressed.
- **`TempFileOnlyStreamCache`**: Raw PDF streams are spooled to temporary scratch storage rather than the JVM heap, preventing `OutOfMemoryError` on 200MB–300MB+ documents.
- **Partition Cleanup**: Memory is explicitly bounded per partition (P1: Parse $\to$ P2: Chunk $\to$ P3: Vectorize). At the boundary of each partition, active buffers are discarded, references severed, and explicit garbage collection sweeps occur, tracking reclaimed heap in audit logs.

### 2. Cross-Platform Engine
- Automatically detects the host runtime environment via `OperatingSystemDetector`:
  - **macOS**: Optimizes memory allocation for Apple Silicon unified memory and Metal acceleration paths.
  - **Windows**: Adapts NIO.2 file channel locks, path delimiters, and handles unbuffered disk streaming.
  - **Linux**: Enables aggressive virtual memory allocation and asynchronous POSIX file operations.
- Real-time manual override available via `POST /api/rag/os-override?os=WINDOWS|MACOS|LINUX`.

### 3. Content-Addressed Chunk Deduplication
- **SHA-256 Fingerprinting**: Each chunk is normalized (whitespace collapsed, casing standardized) and hashed into a 16-hex deterministic fingerprint (`chk_<hash>`).
- **Embedding Reuse**: If a chunk with the same text appears across multiple pages or different documents, the system reuses the existing vector embedding without recomputing it.
- **Multi-Source Citations**: Retains all occurrences (e.g. `docA.pdf:p2`, `docB.pdf:p14`), allowing search results and LLM citations to refer to all matching sources without index bloat.

### 4. Dynamic Hardware Tuning & Live Backpressure
- Dynamic profile switching via `POST /api/rag/hardware-tuning`:
  - **CPU Cores**: Adjust live worker thread counts (e.g., 8 cores, 16 cores) dynamically resizing the active `ThreadPoolExecutor` and concurrency `Semaphore`.
  - **RAM Ceilings**: Configure memory capacity (8 GB, 16 GB, 32 GB), dynamically recalculating the safety ceiling (`maxHeap * 0.85`).
- **Resource Manager**: Actively checks memory pressure (`NORMAL`, `WARNING`, `CRITICAL`). If heap usage exceeds the safety ceiling, workers throttle and yield until memory stabilizes.

### 5. Ocean Breeze Neomorphic Web Studio
- **Palette**: Figma-accurate Ocean Breeze theme:
  - Deep Navy Primary: `#0B3D91`
  - Sky Blue Secondary: `#3BA7F2`
  - Mint / Aqua Tertiary: `#7FE7D6`
  - Soft Canvas Background: `#E8F6FF`
- **Neomorphic Surfaces**: Soft dual-light shadows (`--neo-raised`, `--neo-sunken`, `--neo-raised-pill`) giving tactile physical elevation to panels, buttons, and recessed wells.
- **Real-Time Telemetry**: 3-second heartbeat polling displaying live **CPU load %** (via `com.sun.management.OperatingSystemMXBean`), active thread workers, and JVM heap status in the top bar.
- **Unified Single-Page Architecture**: Single clean interface providing:
  - **Ask Question / Q&A Studio** with instant citations
  - **Pipeline Studio** with drag-and-drop file upload, folder input, partition controls, and live execution logs
  - **Vector DB Management** with ChromaDB status, collection stats, and chunk deduplication metrics
  - **Semantic Vector Search** tester
  - **Audit Jobs & Resume Manager** for monitoring completed, failed, or resumable jobs

---

## 🚀 Quick Start Guide

### Prerequisites
- **Java 17+** (OpenJDK, Amazon Corretto, Eclipse Temurin)
- **Maven 3.9+**
- **Docker** (Optional, for ChromaDB)

### 1. Build and Run in Docker (Recommended)

The easiest and cleanest way to run the complete stack (RAG Pipeline + ChromaDB) is via Docker:

```bash
# Option A: One-click deployment script
./deploy-docker.sh up

# Option B: Standard Docker Compose
docker compose up -d --build
```

#### Docker Management Commands:
| Action | Helper Script | Docker CLI Command |
|--------|---------------|-------------------|
| **Start Stack** | `./deploy-docker.sh up` | `docker compose up -d --build` |
| **Stop Stack** | `./deploy-docker.sh down` | `docker compose down` |
| **View Live Logs** | `./deploy-docker.sh logs` | `docker compose logs -f rag-pipeline` |
| **Check Status** | `./deploy-docker.sh status` | `docker compose ps` |
| **Rebuild Container**| `./deploy-docker.sh build` | `docker compose build --no-cache` |
| **Restart Stack** | `./deploy-docker.sh restart` | `docker compose restart` |

> [!TIP]
> The Docker container exposes the Web Studio on **`http://localhost:8082`** by default (to prevent conflicts with other services running on port 8080). If you want to use port 8080, set `RAG_PORT=8080` in `.env` or run:
> ```bash
> RAG_PORT=8080 docker compose up -d
> ```

---

### 2. Run Locally on Host (Without Docker)

```bash
# 1. Package JAR
mvn clean package -DskipTests

# 2. Launch with production memory bounds
./run-pipeline.sh

# Or direct Java launch:
java -server -Xms512m -Xmx2048m \
  -Dorg.apache.pdfbox.rendering.UsePureJava=true \
  -Dfile.encoding=UTF-8 \
  -jar target/smart-rag-platform-1.0.0.jar --server.port=8080
```

---

### 3. Accessing the Application

- **Web Studio (Docker):** [http://localhost:8082](http://localhost:8082) (or [http://localhost:8080](http://localhost:8080) if run locally)
- **H2 Database Console:** `http://localhost:8082/h2-console`
  - *JDBC URL:* `jdbc:h2:file:./data/pipeline_db`
  - *User:* `sa` (no password)
- **ChromaDB API:** [http://localhost:8005](http://localhost:8005)
  - *Heartbeat:* `http://localhost:8005/api/v1/heartbeat`
  - *Collections:* `http://localhost:8005/api/v1/collections`

---

## 📡 REST API Reference

### Pipeline & Ingestion
| Method | Endpoint | Parameters / Payload | Description |
|--------|----------|----------------------|-------------|
| `POST` | `/api/rag/upload-and-ingest` | `multipart/form-data` (`files`) | Upload one or more PDFs and run full pipeline |
| `POST` | `/api/rag/ingest` | `folderPath` *(optional)* | Ingest all PDFs from disk directory |
| `POST` | `/api/rag/parse-only` | `folderPath` *(optional)* | Run Partition 1 only (PDF $\to$ Markdown) |
| `POST` | `/api/rag/chunk-only` | — | Run Partition 2 only (Markdown $\to$ JSONL) |
| `POST` | `/api/rag/vectorize-only`| — | Run Partition 3 only (JSONL $\to$ Vectors) |
| `POST` | `/api/rag/pipeline/resume`| — | Resumes last interrupted or failed run |
| `GET`  | `/api/rag/pipeline-status`| — | Polls live progress of the active ingestion run |
| `GET`  | `/api/rag/inspect-file` | `filePath` | Read PDF metadata without modifying the file |

### Search & Question Answering
| Method | Endpoint | Parameters | Description |
|--------|----------|------------|-------------|
| `POST` | `/api/rag/ask` | `query` *(required)*, `topK` *(default 5)* | RAG QA synthesis with full multi-source citations |
| `GET`  | `/api/rag/search` | `query` *(required)*, `topK` *(default 5)* | Direct vector cosine similarity search |

### Telemetry, Hardware & System Control
| Method | Endpoint | Parameters / Payload | Description |
|--------|----------|----------------------|-------------|
| `GET`  | `/api/rag/system-metrics` | — | Real-time CPU % load, heap memory, and worker threads |
| `GET`  | `/api/rag/system-info` | — | OS, CPU architecture, GPU, and Java runtime specs |
| `GET`  | `/api/rag/resource-state` | — | Memory health state (`NORMAL`, `WARNING`, `CRITICAL`) |
| `GET`  | `/api/rag/execution-plan` | — | Adaptive batch size and worker concurrency plan |
| `GET`  | `/api/rag/vector-db/status`| — | ChromaDB connectivity and deduplication stats |
| `GET`  | `/api/rag/jobs` | — | Document ingestion audit history from H2 database |
| `POST` | `/api/rag/hardware-tuning` | `{"ramGb": 16, "cores": 16}` | Dynamically adjust workers and memory ceiling |
| `POST` | `/api/rag/os-override` | `os=WINDOWS\|MACOS\|LINUX` | Manually switch OS execution mode |
| `POST` | `/api/rag/gc` | — | Force immediate JVM garbage collection sweep |

---

## 📁 Directory Structure

```
smart-rag-platform/
├── data/
│   ├── raw_pdfs/            # Input folder: Source PDF documents (strictly read-only)
│   ├── staging_md/          # Partition 1: Clean, layout-preserving Markdown files
│   ├── chunked_jsonl/       # Partition 2: Deterministically chunked JSONL records
│   ├── vector_storage/      # Partition 3: Embedded vector records (disk fallback)
│   └── pipeline_db.mv.db    # Embedded H2 audit database
├── frontend/
│   └── index.html           # Standalone mirror of the Neomorphic Web Studio
├── src/
│   ├── main/
│   │   ├── java/com/nextgem/smartrag/
│   │   │   ├── SmartRagPlatformApplication.java     # Spring Boot main entry
│   │   │   ├── config/
│   │   │   │   ├── ConcurrencyConfig.java           # Thread pool & Virtual Threads
│   │   │   │   ├── GracefulShutdownHandler.java     # Clean SIGTERM task draining
│   │   │   │   ├── PipelineStartupInitializer.java  # Auto directory verification
│   │   │   │   └── RagPipelineProperties.java       # External configuration bindings
│   │   │   ├── controller/
│   │   │   │   └── RagIngestionController.java      # REST endpoints & telemetry API
│   │   │   ├── model/
│   │   │   │   └── DocumentJob.java                 # JPA audit entity
│   │   │   ├── orchestrator/
│   │   │   │   └── RagPipelineOrchestrator.java     # 3-Partition workflow coordinator
│   │   │   ├── parser/
│   │   │   │   ├── MarkdownStructuralPdfStripper.java # Page-by-page PDF parser
│   │   │   │   ├── PageBatchProcessor.java          # Batch streaming page processor
│   │   │   │   └── PdfParallelParserService.java    # Multi-threaded PDF parser
│   │   │   ├── repository/
│   │   │   │   └── DocumentJobRepository.java       # Spring Data JPA repository
│   │   │   ├── service/
│   │   │   │   ├── CheckpointService.java           # Pipeline checkpoint tracker
│   │   │   │   ├── DynamicHardwareTuningService.java # Dynamic core & RAM tuner
│   │   │   │   ├── OperatingSystemDetector.java     # OS & hardware detection
│   │   │   │   ├── PerformanceController.java       # Hardware performance stats
│   │   │   │   ├── RagChunkingService.java          # Semantic boundary chunker
│   │   │   │   ├── RagGenerationService.java        # LLM routing & answer synthesis
│   │   │   │   └── ResourceManager.java             # Heap pressure monitor
│   │   │   └── vectorstore/
│   │   │       ├── ChromaVectorStoreService.java    # ChromaDB & SHA-256 chunk dedup
│   │   │       └── VectorDocument.java              # Vector payload structure
│   │   └── resources/
│   │       ├── application.yml                      # Application configuration
│   │       └── static/
│   │           └── index.html                       # Embedded Neomorphic Web Studio
│   └── test/
│       └── java/com/nextgem/smartrag/               # Complete JUnit test suite (12 tests)
├── docker-compose.yml                               # Local ChromaDB & App container setup
├── Dockerfile                                       # Multi-stage production container build
├── deploy-docker.sh                                 # One-click Docker management script
├── pom.xml                                          # Maven project dependencies
├── README.md                                        # System documentation
└── run-pipeline.sh                                  # Production launch script with JVM flags
```

---

## 🧪 Verification & Automated Tests

The codebase includes an automated JUnit 5 test suite verifying pipeline resilience, deduplication, hardware adaptation, and thread bounds:

```bash
mvn test
```

### Test Coverage Summary:
- **`EndToEndRagPipelineTest`**: End-to-end flow from synthetic PDF generation to parsing, chunking, vector indexing, similarity search, and answer generation.
- **`ChunkDeduplicationTest`**: Verifies deterministic SHA-256 fingerprinting and validates that duplicate text across multiple files reuses existing embeddings while aggregating multi-source citations.
- **`DynamicHardwareTuningTest`**: Tests live reconfiguration between 8-core / 8GB and 16-core / 16GB–32GB profiles.
- **`OperatingSystemDetectorTest`**: Verifies platform detection across Windows, macOS, and Linux modes.
- **`ResourceManagerTest`**: Tests memory ceiling calculation, pause-on-pressure throttling, and GC sweeps.
- **`PageBatchProcessorTest`**: Validates page-by-page streaming and chunk batching without memory retention.
- **`SmartRagPlatformApplicationTests`**: Validates full Spring application context loading.

---

## 🔒 Memory Safety & Production Guidelines

When deploying in production, ensure JVM memory flags are tuned to the host system capacity:

```bash
# Recommended baseline for 8GB RAM host:
java -server \
  -Xms1024m -Xmx4096m \
  -XX:MaxDirectMemorySize=1024m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -XX:G1ReservePercent=15 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -Dorg.apache.pdfbox.rendering.UsePureJava=true \
  -Dfile.encoding=UTF-8 \
  -jar target/smart-rag-platform-1.0.0.jar
```

- **Heap Ceiling**: The internal `ResourceManager` automatically maintains a maximum active threshold at **85% of `-Xmx`**.
- **Worker Allocation**: Bounded concurrency avoids thread pool starvation and memory exhaustion during heavy PDF parsing.
- **Data Integrity**: Source PDFs are opened exclusively in read-only mode using `TempFileOnlyStreamCache` to ensure zero file mutation and zero silent data loss.

# Start / deploy the stack in background
./deploy-docker.sh up

# View real-time logs
./deploy-docker.sh logs

# Check container status
./deploy-docker.sh status

# Rebuild image after code changes
./deploy-docker.sh build

# Restart containers
./deploy-docker.sh restart

# Stop the stack
./deploy-docker.sh down



# 1. Build and start containers in the background
docker compose up -d --build

# 2. View live logs
docker compose logs -f rag-pipeline

# 3. Check container health & port mapping
docker compose ps

# 4. Stop all containers
docker compose down

# 5. Run on port 8080 (if port 8080 is available on your host)
RAG_PORT=8080 docker compose up -d