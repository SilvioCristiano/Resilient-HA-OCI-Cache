package com.example.ocicache.api;

import com.example.ocicache.config.HaCacheProperties;
import com.example.ocicache.core.HaCacheRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CacheController {

    private final HaCacheRouter cache;
    private final HaCacheProperties properties;
    private final ObjectMapper objectMapper;

    public CacheController(
            HaCacheRouter cache,
            HaCacheProperties properties,
            ObjectMapper objectMapper) {
        this.cache = cache;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public PublishedEvent publish(
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @RequestBody @NotNull JsonNode payload) {
        String redisId = cache.publish(
                properties.getStream().getKey(),
                idempotencyKey,
                payload.toString(),
                properties.getStream().getDeduplicationTtl());
        return new PublishedEvent(idempotencyKey, redisId, cache.status().get("activeRegion").toString());
    }

    @PostMapping("/cache/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(
            @PathVariable @NotBlank String key,
            @Valid @RequestBody CacheValue body) {
        cache.set(key, body.value(), body.ttl());
    }

    @GetMapping("/cache/{key}")
    public Map<String, Object> get(@PathVariable @NotBlank String key) {
        String value = cache.get(key);
        return Map.of("key", key, "found", value != null, "value", value == null ? "" : value);
    }

    @GetMapping("/cache-status")
    public Map<String, Object> status() {
        return cache.status();
    }

    public record CacheValue(
            @NotBlank String value,
            @NotNull Duration ttl) {
    }

    public record PublishedEvent(String eventId, String redisId, String region) {
    }
}
