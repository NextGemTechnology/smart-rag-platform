#!/usr/bin/env bash
# ==============================================================================
# Enterprise RAG Ingestion Pipeline - Docker Deployment Script
# ==============================================================================
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

ACTION="${1:-up}"

case "$ACTION" in
  build)
    echo "🔨 Building Docker image with Docker Compose..."
    docker compose build --no-cache
    ;;

  up|start)
    echo "🚀 Starting Enterprise RAG Pipeline in Docker..."
    # Ensure local data directory structure exists
    mkdir -p data/raw_pdfs data/staging_md data/chunked_jsonl data/vector_storage
    docker compose up -d --build
    echo ""
    echo "✅ RAG Pipeline & ChromaDB containers are running!"
    echo "🌐 Web Studio: http://localhost:${RAG_PORT:-8082}"
    echo "📊 ChromaDB API: http://localhost:8005"
    echo ""
    echo "View logs with: ./deploy-docker.sh logs"
    ;;

  down|stop)
    echo "🛑 Stopping RAG Pipeline containers..."
    docker compose down
    ;;

  logs)
    docker compose logs -f rag-pipeline
    ;;

  status|ps)
    docker compose ps
    ;;

  restart)
    echo "🔄 Restarting RAG Pipeline..."
    docker compose restart
    ;;

  *)
    echo "Usage: $0 {start|up|build|stop|down|restart|logs|status}"
    exit 1
    ;;
esac
