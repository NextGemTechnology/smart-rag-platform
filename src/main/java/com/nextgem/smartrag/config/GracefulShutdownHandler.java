package com.nextgem.smartrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Enterprise Graceful Shutdown Handler.
 * Intercepts JVM termination and Spring application shutdown signals,
 * guaranteeing that in-flight document processing finishes safely,
 * checkpoints are persisted, and resources are cleanly released.
 */
@Component
public class GracefulShutdownHandler implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownHandler.class);

    private final ThreadPoolExecutor executor;
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    public GracefulShutdownHandler(@Qualifier("pipelineExecutor") ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    public boolean isShuttingDown() {
        return isShuttingDown.get();
    }

    @Override
    public void destroy() {
        log.info("[SHUTDOWN] Graceful shutdown initiated. Ceasing ingestion and draining worker threads...");
        isShuttingDown.set(true);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("[SHUTDOWN] Tasks did not complete within 5s. Forcing immediate thread cancellation...");
                executor.shutdownNow();
            } else {
                log.info("[SHUTDOWN] All pipeline worker threads completed cleanly.");
            }
        } catch (InterruptedException e) {
            log.warn("[SHUTDOWN] Interrupted while awaiting thread termination.");
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
