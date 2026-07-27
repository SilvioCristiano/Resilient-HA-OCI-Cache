package com.example.ocicache.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ocicache.config.HaCacheProperties;
import com.example.ocicache.oci.CacheProvisioner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HaCacheRouterTest {

    private HaCacheRouter router;

    /**
     * Permite executar os mesmos cenários sem depender do runner do JUnit,
     * útil em ambientes de build isolados.
     */
    public static void main(String[] args) {
        HaCacheRouterTest test = new HaCacheRouterTest();
        try {
            test.switchesToStandbyAndRetriesAnIdempotentWrite();
        } finally {
            test.tearDown();
        }
        test = new HaCacheRouterTest();
        try {
            test.returnsUnavailableWhenNoRegionIsHealthy();
        } finally {
            test.tearDown();
        }
        test.backoffIsExponentialCappedAndJittered();
        test = new HaCacheRouterTest();
        try {
            test.opensCircuitBreakerAfterConfiguredFailureRate();
        } finally {
            test.tearDown();
        }
        System.out.println("HaCacheRouterTest: 4 cenários aprovados");
    }

    @AfterEach
    void tearDown() {
        if (router != null) {
            router.close();
        }
    }

    @Test
    void switchesToStandbyAndRetriesAnIdempotentWrite() {
        HaCacheProperties properties = properties();
        FakeNode primary = new FakeNode("primary", "region-a");
        FakeNode standby = new FakeNode("standby", "region-b");
        FakeFactory factory = new FakeFactory(List.of(primary, standby));
        router = new HaCacheRouter(
                properties, factory, noProvisioning(), new SimpleMeterRegistry(), Runnable::run);
        router.initialize();

        primary.commandHealthy = false;
        router.set("customer:42", "active", Duration.ofMinutes(5));

        assertThat(router.status().get("activeRegion")).isEqualTo("region-b");
        assertThat(standby.values).containsEntry("customer:42", "active");
        System.out.println("✅ Failover primary → standby passou");
    }

    @Test
    void returnsUnavailableWhenNoRegionIsHealthy() {
        HaCacheProperties properties = properties();
        properties.getFailover().setCommandAttempts(1);
        FakeNode primary = new FakeNode("primary", "region-a");
        FakeNode standby = new FakeNode("standby", "region-b");
        FakeFactory factory = new FakeFactory(List.of(primary, standby));
        router = new HaCacheRouter(
                properties, factory, noProvisioning(), new SimpleMeterRegistry(), Runnable::run);
        router.initialize();
        primary.commandHealthy = false;
        standby.pingHealthy = false;

        assertThatThrownBy(() -> router.get("key"))
                .isInstanceOf(CacheUnavailableException.class);
        assertThat(router.status().get("activeRegion")).isEqualTo("region-a");
        System.out.println("✅ Indisponibilidade total passou");
    }

    @Test
    void backoffIsExponentialCappedAndJittered() {
        assertThat(HaCacheRouter.calculateBackoffMillis(500, 5_000, 0.5, 1, 0.0))
                .isEqualTo(250);
        assertThat(HaCacheRouter.calculateBackoffMillis(500, 5_000, 0.5, 3, 0.5))
                .isEqualTo(2_000);
        assertThat(HaCacheRouter.calculateBackoffMillis(500, 5_000, 0.5, 10, 1.0))
                .isEqualTo(5_000);
        System.out.println("✅ Backoff exponencial com jitter passou");
    }

    @Test
    void opensCircuitBreakerAfterConfiguredFailureRate() {
        HaCacheProperties properties = properties();
        properties.getFailover().setCommandAttempts(1);
        properties.getFailover().setFailureThreshold(100);
        properties.getFailover().setCircuitBreakerSlidingWindowSize(2);
        properties.getFailover().setCircuitBreakerMinimumCalls(2);
        FakeNode primary = new FakeNode("primary", "region-a");
        FakeNode standby = new FakeNode("standby", "region-b");
        router = new HaCacheRouter(
                properties,
                new FakeFactory(List.of(primary, standby)),
                noProvisioning(),
                new SimpleMeterRegistry(),
                Runnable::run);
        router.initialize();
        primary.commandHealthy = false;

        assertThatThrownBy(() -> router.get("key")).isInstanceOf(CacheUnavailableException.class);
        assertThatThrownBy(() -> router.get("key")).isInstanceOf(CacheUnavailableException.class);

        assertThat(router.status().get("circuitBreaker")).isEqualTo("OPEN");
        System.out.println("✅ Circuit breaker passou");
    }

    private HaCacheProperties properties() {
        HaCacheProperties properties = new HaCacheProperties();
        properties.setRegions(List.of(region("primary"), region("standby")));
        properties.getFailover().setFailureThreshold(1);
        properties.getFailover().setCommandAttempts(2);
        properties.getFailover().setRetryBackoff(Duration.ZERO);
        return properties;
    }

    private HaCacheProperties.Region region(String name) {
        HaCacheProperties.Region region = new HaCacheProperties.Region();
        region.setName(name);
        region.setOciRegion(name);
        region.setHosts(List.of(name + ".example"));
        return region;
    }

    private CacheProvisioner noProvisioning() {
        return Optional::empty;
    }

    private static final class FakeFactory implements CacheNodeFactory {
        private final List<FakeNode> nodes;
        private int index;

        private FakeFactory(List<FakeNode> nodes) {
            this.nodes = new ArrayList<>(nodes);
        }

        @Override
        public CacheNode create(HaCacheProperties.Region ignored) {
            return nodes.get(index++);
        }
    }

    private static final class FakeNode implements CacheNode {
        private final String name;
        private final String region;
        private final Map<String, String> values = new HashMap<>();
        private boolean pingHealthy = true;
        private boolean commandHealthy = true;

        private FakeNode(String name, String region) {
            this.name = name;
            this.region = region;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String region() {
            return region;
        }

        @Override
        public boolean ping() {
            return pingHealthy;
        }

        @Override
        public String get(String key) {
            checkCommand();
            return values.get(key);
        }

        @Override
        public String getPrimary(String key) {
            return get(key);
        }

        @Override
        public void set(String key, String value, Duration ttl) {
            checkCommand();
            values.put(key, value);
        }

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            checkCommand();
            return values.putIfAbsent(key, value) == null;
        }

        @Override
        public void delete(String key) {
            checkCommand();
            values.remove(key);
        }

        @Override
        public long increment(String key) {
            checkCommand();
            long next = Long.parseLong(values.getOrDefault(key, "0")) + 1;
            values.put(key, Long.toString(next));
            return next;
        }

        @Override
        public String publishIdempotently(
                String stream, String eventId, String payload, Duration ttl) {
            checkCommand();
            return "1-0";
        }

        @Override
        public void ensureConsumerGroup(String stream, String group) {
            checkCommand();
        }

        @Override
        public List<CacheMessage> readGroup(
                String stream, String group, String consumer, int count, Duration block) {
            checkCommand();
            return List.of();
        }

        @Override
        public List<CacheMessage> claimPending(
                String stream,
                String group,
                String consumer,
                int count,
                Duration minIdle) {
            checkCommand();
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
            checkCommand();
        }

        @Override
        public void close() {
        }

        private void checkCommand() {
            if (!commandHealthy) {
                throw new IllegalStateException("connection lost");
            }
        }
    }
}
