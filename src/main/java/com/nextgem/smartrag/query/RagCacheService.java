package com.nextgem.smartrag.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nextgem.smartrag.service.RagGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Multi-Tier High-Throughput Caching Service (L1 JVM In-Memory + L2 Distributed Redis).
 * Ensures instant sub-millisecond responses for repeated queries and eliminates duplicate vector calculations.
 */
@Service
public class RagCacheService {

    private static final Logger log = LoggerFactory.getLogger(RagCacheService.class);

    private static final Duration DEFAULT_ANSWER_TTL = Duration.ofMinutes(15);
    private static final Duration DEFAULT_EMBEDDING_TTL = Duration.ofHours(2);
    private static final int L1_MAX_SIZE = 5000;

    private final ObjectMapper objectMapper;
    private final QueryMetricsTracker metricsTracker;
    private final StringRedisTemplate redisTemplate;

    // L1 In-Memory LRU/TTL Cache records
    private record CacheEntry<T>(T value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, CacheEntry<RagGenerationService.RagAnswer>> l1AnswerCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry<float[]>> l1EmbeddingCache = new ConcurrentHashMap<>();

    private volatile boolean redisOperational = false;

    public RagCacheService(
            ObjectMapper objectMapper,
            QueryMetricsTracker metricsTracker,
            @Autowired(required = false) StringRedisTemplate redisTemplate
    ) {
        this.objectMapper = objectMapper;
        this.metricsTracker = metricsTracker;
        this.redisTemplate = redisTemplate;
        testRedisConnectivity();
    }

    private void testRedisConnectivity() {
        if (redisTemplate != null) {
            try {
                redisTemplate.getConnectionFactory().getConnection().ping();
                redisOperational = true;
                log.info("[CACHE] Connected to distributed Redis cache successfully.");
            } catch (Exception e) {
                redisOperational = false;
                log.warn("[CACHE] Redis unavailable ({}), using high-speed in-memory L1 cache.", e.getMessage());
            }
        } else {
            redisOperational = false;
            log.info("[CACHE] No Redis configured, active in L1 JVM in-memory caching mode.");
        }
    }

    /**
     * Retrieves cached RagAnswer from L1 memory or L2 Redis.
     */
    public RagGenerationService.RagAnswer getAnswer(String query, int topK) {
        String key = "rag:answer:" + hashKey(query.trim().toLowerCase() + ":" + topK);

        // 1. Check L1 Memory
        CacheEntry<RagGenerationService.RagAnswer> l1Entry = l1AnswerCache.get(key);
        if (l1Entry != null) {
            if (!l1Entry.isExpired()) {
                metricsTracker.recordCacheHit();
                return l1Entry.value();
            } else {
                l1AnswerCache.remove(key);
            }
        }

        // 2. Check L2 Redis
        if (redisOperational && redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null && !json.isBlank()) {
                    RagGenerationService.RagAnswer answer = objectMapper.readValue(json, RagGenerationService.RagAnswer.class);
                    // Populate back into L1
                    putL1Answer(key, answer, DEFAULT_ANSWER_TTL);
                    metricsTracker.recordCacheHit();
                    return answer;
                }
            } catch (Exception e) {
                handleRedisError(e);
            }
        }

        metricsTracker.recordCacheMiss();
        return null;
    }

    /**
     * Stores answer into L1 memory and L2 Redis.
     */
    public void putAnswer(String query, int topK, RagGenerationService.RagAnswer answer) {
        if (answer == null) return;
        String key = "rag:answer:" + hashKey(query.trim().toLowerCase() + ":" + topK);

        putL1Answer(key, answer, DEFAULT_ANSWER_TTL);

        if (redisOperational && redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(answer);
                redisTemplate.opsForValue().set(key, json, DEFAULT_ANSWER_TTL);
            } catch (Exception e) {
                handleRedisError(e);
            }
        }
    }

    /**
     * Retrieves cached query embedding vector from L1 or L2 Redis.
     */
    public float[] getEmbedding(String text) {
        String key = "rag:emb:" + hashKey(text.trim().toLowerCase());

        CacheEntry<float[]> l1Entry = l1EmbeddingCache.get(key);
        if (l1Entry != null) {
            if (!l1Entry.isExpired()) {
                return l1Entry.value();
            } else {
                l1EmbeddingCache.remove(key);
            }
        }

        if (redisOperational && redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(key);
                if (json != null && !json.isBlank()) {
                    float[] emb = objectMapper.readValue(json, float[].class);
                    putL1Embedding(key, emb, DEFAULT_EMBEDDING_TTL);
                    return emb;
                }
            } catch (Exception e) {
                handleRedisError(e);
            }
        }

        return null;
    }

    /**
     * Caches computed embedding vector.
     */
    public void putEmbedding(String text, float[] embedding) {
        if (embedding == null) return;
        String key = "rag:emb:" + hashKey(text.trim().toLowerCase());

        putL1Embedding(key, embedding, DEFAULT_EMBEDDING_TTL);

        if (redisOperational && redisTemplate != null) {
            try {
                String json = objectMapper.writeValueAsString(embedding);
                redisTemplate.opsForValue().set(key, json, DEFAULT_EMBEDDING_TTL);
            } catch (Exception e) {
                handleRedisError(e);
            }
        }
    }

    private void putL1Answer(String key, RagGenerationService.RagAnswer answer, Duration ttl) {
        if (l1AnswerCache.size() >= L1_MAX_SIZE) {
            evictExpiredL1();
            if (l1AnswerCache.size() >= L1_MAX_SIZE) {
                l1AnswerCache.clear(); // Safe bounded eviction under extreme burst
            }
        }
        l1AnswerCache.put(key, new CacheEntry<>(answer, System.currentTimeMillis() + ttl.toMillis()));
    }

    private void putL1Embedding(String key, float[] embedding, Duration ttl) {
        if (l1EmbeddingCache.size() >= L1_MAX_SIZE) {
            l1EmbeddingCache.clear();
        }
        l1EmbeddingCache.put(key, new CacheEntry<>(embedding, System.currentTimeMillis() + ttl.toMillis()));
    }

    private void evictExpiredL1() {
        l1AnswerCache.entrySet().removeIf(e -> e.getValue().isExpired());
        l1EmbeddingCache.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private void handleRedisError(Exception e) {
        redisOperational = false;
        log.warn("[CACHE] Redis operation failed ({}), falling back to in-memory L1 cache.", e.getMessage());
    }

    private String hashKey(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    public boolean isRedisOperational() {
        return redisOperational;
    }

    public int getL1CacheSize() {
        return l1AnswerCache.size();
    }
}
