package com.nextgem.smartrag.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class ConcurrencyConfig {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConfig.class);

    @Bean(name = "pipelineExecutor")
    public ThreadPoolExecutor pipelineExecutor(RagPipelineProperties properties) {
        int cores = properties.getMaxConcurrency();
        log.info("Initializing bounded ThreadPoolExecutor with {} core workers.", cores);

        return new ThreadPoolExecutor(
                cores,
                cores * 2,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                new ThreadFactory() {
                    private final AtomicInteger count = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "rag-worker-" + count.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // Backpressure to avoid OOM
        );
    }
}
