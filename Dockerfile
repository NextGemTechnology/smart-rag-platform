# ==============================================================================
# Enterprise High-Throughput RAG Ingestion Pipeline - Dockerfile
# Multi-stage build for cross-platform containerization (x86_64 / ARM64)
# ==============================================================================

# --- Stage 1: Build Application JAR ---
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build

# Copy source code and package
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# --- Stage 2: Production JRE Runtime ---
FROM eclipse-temurin:17-jre
WORKDIR /app

# Install curl for container health check
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create pipeline working directories
RUN mkdir -p /app/data/raw_pdfs \
             /app/data/staging_md \
             /app/data/chunked_jsonl \
             /app/data/vector_storage

# Copy built JAR from builder stage
COPY --from=builder /build/target/smart-rag-platform-1.0.0.jar /app/app.jar

# Default environment configuration
ENV PORT=8080 \
    CHROMA_URL=http://chromadb:8000 \
    JAVA_OPTS="-server -Xms512m -Xmx2048m -XX:MaxDirectMemorySize=1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=45 -Dorg.apache.pdfbox.rendering.UsePureJava=true -Dfile.encoding=UTF-8"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/api/rag/system-metrics || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar --server.port=${PORT}"]
