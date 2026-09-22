#!/usr/bin/env bash
# ==============================================================================
# Enterprise High-Throughput RAG Pipeline Startup Script
# Configured for 8-Core CPU / 8GB Total RAM Baseline
# ==============================================================================
set -e

APP_JAR="target/smart-rag-platform-1.0.0.jar"

if [ ! -f "$APP_JAR" ]; then
    echo "📦 Building project JAR with Maven..."
    mvn clean package -DskipTests
fi

PORT="${PORT:-8080}"
echo "🚀 Launching Spring Boot RAG Pipeline on port $PORT with 8GB RAM JVM Tuning..."

exec java -server \
  -Xms2560m \
  -Xmx4096m \
  -XX:MaxDirectMemorySize=1024m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=50 \
  -XX:G1ReservePercent=15 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -Dorg.apache.pdfbox.rendering.UsePureJava=true \
  -Dfile.encoding=UTF-8 \
  -Dserver.port="$PORT" \
  -jar "$APP_JAR"
