package com.example.ocicache.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ocicache.config.HaCacheProperties;
import com.example.ocicache.core.CacheMessage;
import com.example.ocicache.core.CacheNode;
import com.example.ocicache.core.HaCacheRouter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StreamConsumerTest {

    private HaCacheRouter router;

    public static void main(String[] args) {
        StreamConsumerTest test = new StreamConsumerTest();
        try {
            test.acksOnlyAfterSuccess();
            test.close();
            test.retriesThenMovesAtomicallyToDeadLetter();
            test.close();
            test.permanentFailureGoesDirectlyToDeadLetter();
            test.close();
            test.ackTransportFailureDoesNotConsumeBusinessAttempt();
            test.close();
            test.monitorsPelAndAttemptsOrphanReclaim();
        } finally {
            test.close();
        }
        System.out.println("StreamConsumerTest: 5 cenários aprovados");
    }

    @AfterEach
    void close() {
        if (router != null) {
            router.close();
            router = null;
        }
    }

    @Test
    void acksOnlyAfterSuccess() {
        FakeNode node = new FakeNode();
        StreamConsumer consumer = consumer(node, message -> { });

        consumer.poll();

        assertThat(node.acknowledged).isTrue();
        assertThat(node.deadLettered).isFalse();
        System.out.println("✅ XACK somente após processamento bem-sucedido passou");
    }

    @Test
    void retriesThenMovesAtomicallyToDeadLetter() {
        FakeNode node = new FakeNode();
        HaCacheProperties properties = properties();
        properties.getStream().setMaxProcessingAttempts(2);
        StreamConsumer consumer = consumer(node, properties, message -> {
            throw new IllegalStateException("dependência temporariamente indisponível");
        });

        consumer.poll();
        assertThat(node.acknowledged).isFalse();
        assertThat(node.deadLettered).isFalse();

        consumer.poll();
        assertThat(node.deadLettered).isTrue();
        assertThat(node.failureAttempts).isEqualTo(2);
        System.out.println("✅ Retry e Dead Letter Stream passaram");
    }

    @Test
    void permanentFailureGoesDirectlyToDeadLetter() {
        FakeNode node = new FakeNode();
        StreamConsumer consumer = consumer(node, message -> {
            throw new PermanentMessageProcessingException("payload inválido");
        });

        consumer.poll();

        assertThat(node.deadLettered).isTrue();
        assertThat(node.failureAttempts).isZero();
        System.out.println("✅ Erro permanente enviado diretamente à DLQ passou");
    }

    @Test
    void ackTransportFailureDoesNotConsumeBusinessAttempt() {
        FakeNode node = new FakeNode();
        node.ackFailure = true;
        StreamConsumer consumer = consumer(node, message -> { });

        consumer.poll();

        assertThat(node.acknowledged).isFalse();
        assertThat(node.failureAttempts).isZero();
        assertThat(node.deadLettered).isFalse();
        System.out.println("✅ Falha de transporte no XACK não consumiu tentativa de negócio");
    }

    @Test
    void monitorsPelAndAttemptsOrphanReclaim() {
        FakeNode node = new FakeNode();
        StreamConsumer consumer = consumer(node, message -> { });

        consumer.poll();
        consumer.monitorPendingEntries();

        assertThat(node.claimCalls).isEqualTo(1);
        assertThat(node.pendingCountCalls).isEqualTo(1);
        System.out.println("✅ Monitoramento XPENDING e reclaim de mensagens órfãs passaram");
    }

    private StreamConsumer consumer(FakeNode node, ThrowingHandler handler) {
        return consumer(node, properties(), handler);
    }

    private StreamConsumer consumer(
            FakeNode node,
            HaCacheProperties properties,
            ThrowingHandler handler) {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        router = new HaCacheRouter(
                properties, ignored -> node, Optional::empty, meters, Runnable::run);
        router.initialize();
        BusinessMessageHandler businessHandler = new BusinessMessageHandler() {
            @Override
            public void handle(CacheMessage message) {
                handler.handle(message);
            }
        };
        return new StreamConsumer(router, properties, businessHandler, meters);
    }

    private HaCacheProperties properties() {
        HaCacheProperties properties = new HaCacheProperties();
        HaCacheProperties.Region region = new HaCacheProperties.Region();
        region.setName("primary");
        region.setOciRegion("region-a");
        region.setHosts(List.of("primary.example"));
        properties.setRegions(List.of(region));
        properties.getFailover().setRetryBackoff(Duration.ZERO);
        properties.getFailover().setCircuitBreakerEnabled(false);
        return properties;
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(CacheMessage message);
    }

    private static final class FakeNode implements CacheNode {
        private final CacheMessage message =
                new CacheMessage("1-0", "event-1", "{}", Instant.now());
        private final Map<String, Long> failures = new HashMap<>();
        private boolean delivered;
        private boolean acknowledged;
        private boolean deadLettered;
        private boolean ackFailure;
        private long failureAttempts;
        private int claimCalls;
        private int pendingCountCalls;

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
            return null;
        }

        @Override
        public String getPrimary(String key) {
            return null;
        }

        @Override
        public void set(String key, String value, Duration ttl) {
        }

        @Override
        public boolean setIfAbsent(String key, String value, Duration ttl) {
            return true;
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public long increment(String key) {
            return 1;
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
            if (delivered || acknowledged) {
                return List.of();
            }
            delivered = true;
            return List.of(message);
        }

        @Override
        public List<CacheMessage> claimPending(
                String stream,
                String group,
                String consumer,
                int count,
                Duration minIdle) {
            claimCalls++;
            return delivered && !acknowledged ? List.of(message) : List.of();
        }

        @Override
        public long pendingCount(String stream, String group) {
            pendingCountCalls++;
            return delivered && !acknowledged ? 1 : 0;
        }

        @Override
        public long recordProcessingFailure(
                String stream, String messageId, String attemptToken, Duration ttl) {
            failureAttempts = failures.merge(messageId, 1L, Long::sum);
            return failureAttempts;
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
            deadLettered = true;
            acknowledged = true;
            return "2-0";
        }

        @Override
        public void acknowledge(String stream, String group, String messageId) {
            if (ackFailure) {
                throw new IllegalStateException("resposta do ACK perdida");
            }
            acknowledged = true;
        }

        @Override
        public void close() {
        }
    }
}
