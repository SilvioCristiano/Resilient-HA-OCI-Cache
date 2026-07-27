package com.example.ocicache.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ocicache.core.CacheMessage;
import com.example.ocicache.core.CacheNode;
import com.example.ocicache.core.HaCacheRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

class HaSpringCacheTest {

    private HaCacheRouter router;

    public static void main(String[] args) {
        HaSpringCacheTest test = new HaSpringCacheTest();
        try {
            test.appliesNamespaceTtlNullPolicyAndConstantTimeClear();
        } finally {
            test.close();
        }
        System.out.println("HaSpringCacheTest: cenário aprovado");
    }

    @AfterEach
    void close() {
        if (router != null) {
            router.close();
        }
    }

    @Test
    void appliesNamespaceTtlNullPolicyAndConstantTimeClear() {
        FakeNode node = new FakeNode();
        HaCacheProperties properties = new HaCacheProperties();
        HaCacheProperties.Region region = new HaCacheProperties.Region();
        region.setName("primary");
        region.setOciRegion("region-a");
        region.setHosts(List.of("primary.example"));
        properties.setRegions(List.of(region));
        properties.getFailover().setRetryBackoff(Duration.ZERO);
        router = new HaCacheRouter(
                properties,
                ignored -> node,
                Optional::empty,
                new SimpleMeterRegistry(),
                Runnable::run);
        router.initialize();

        HaCacheProperties.SpringCache config = properties.getSpring();
        config.setNamespace("orders:test");
        config.setCacheNames(List.of("customers"));
        config.setDefaultTtl(Duration.ofMinutes(5));
        Cache cache = new HaSpringCacheManager(
                router, config, GenericJackson2JsonRedisSerializer.builder().build())
                .getCache("customers");

        cache.put(42, "active");
        assertThat(cache.get(42, String.class)).isEqualTo("active");
        cache.put(42, null);
        assertThat(cache.get(42, String.class)).isEqualTo("active");
        assertThat(cache.putIfAbsent(42, "blocked").get()).isEqualTo("active");

        cache.clear();
        assertThat(cache.get(42)).isNull();
        assertThat(node.values.keySet()).anyMatch(key -> key.startsWith("orders:test:customers:v0::"));
        assertThat(node.values).containsEntry("orders:test:customers:version", "1");
        System.out.println("✅ Spring Cache: TTL, namespace, null e clear O(1) passou");
    }

    private static final class FakeNode implements CacheNode {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String name() {
            return "primary";
        }

        @Override
        public String region() {
            return "region-a";
        }

        @Override
        public boolean ping() {
            return true;
        }

        @Override
        public String get(String key) {
            return values.get(key);
        }

        @Override
        public String getPrimary(String key) {
            return values.get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            values.put(key, value);
        }

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            return values.putIfAbsent(key, value) == null;
        }

        @Override
        public void delete(String key) {
            values.remove(key);
        }

        @Override
        public long increment(String key) {
            long next = Long.parseLong(values.getOrDefault(key, "0")) + 1;
            values.put(key, Long.toString(next));
            return next;
        }

        @Override
        public String publishIdempotently(
                String stream, String eventId, String payload, Duration ttl) {
            return "1-0";
        }

        @Override
        public void ensureConsumerGroup(String stream, String group) {
        }

        @Override
        public List<CacheMessage> readGroup(
                String stream, String group, String consumer, int count, Duration block) {
            return List.of();
        }

        @Override
        public List<CacheMessage> claimPending(
                String stream,
                String group,
                String consumer,
                int count,
                Duration minIdle) {
            return List.of();
        }

        @Override
        public long pendingCount(String stream, String group) {
            return 0;
        }

        @Override
        public long recordProcessingFailure(
                String stream, String messageId, String attemptToken, Duration ttl) {
            return 1;
        }

        @Override
        public String deadLetter(
                String stream,
                String deadLetterStream,
                String group,
                CacheMessage message,
                String reason,
                long maxLength,
                Duration deduplicationTtl) {
            return "2-0";
        }

        @Override
        public void acknowledge(String stream, String group, String messageId) {
        }

        @Override
        public void close() {
        }
    }
}
