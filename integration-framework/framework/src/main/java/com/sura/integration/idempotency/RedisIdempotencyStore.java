package com.sura.integration.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sura.integration.model.IntegrationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "integration:idempotency:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final IdempotencyKeyGenerator keyGenerator;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  IdempotencyKeyGenerator keyGenerator) {
        this.redisTemplate  = redisTemplate;
        this.objectMapper   = objectMapper;
        this.keyGenerator   = keyGenerator;
    }

    @Override
    public Optional<CachedResponse> get(String key) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, CachedResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached response for key '{}': {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void store(String key, IntegrationResponse response, Duration ttl) {
        try {
            String bodyJson = objectMapper.writeValueAsString(response.body());
            CachedResponse cached = new CachedResponse(
                    response.statusCode(),
                    bodyJson,
                    response.responseHeaders(),
                    Instant.now());
            String json = objectMapper.writeValueAsString(cached);
            // SET NX PX — first-write-wins, atomic, no race condition
            Boolean stored = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + key, json, ttl);
            if (Boolean.FALSE.equals(stored)) {
                log.debug("Idempotency key '{}' already exists in Redis (first-write-wins)", key);
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize response for idempotency key '{}': {}", key, e.getMessage());
        }
    }

    @Override
    public String generateKey() {
        return keyGenerator.generate();
    }
}
