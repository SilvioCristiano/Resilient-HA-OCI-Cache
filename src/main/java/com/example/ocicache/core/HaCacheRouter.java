package com.example.ocicache.core;

import com.example.ocicache.config.HaCacheProperties;
import com.example.ocicache.oci.CacheProvisioner;
import io.lettuce.core.RedisCommandExecutionException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.core.task.TaskExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class HaCacheRouter {

    private static final Logger log = LoggerFactory.getLogger(HaCacheRouter.class);

    private final HaCacheProperties properties;
    private final CacheNodeFactory nodeFactory;
    private final CacheProvisioner provisioner;
    private final MeterRegistry meterRegistry;
    private final TaskExecutor provisioningExecutor;
    private final CopyOnWriteArrayList<CacheNode> nodes = new CopyOnWriteArrayList<>();
    private final Map<CacheNode, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final AtomicReference<CacheNode> active = new AtomicReference<>();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicBoolean provisioning = new AtomicBoolean();
    private final Counter failovers;
    private volatile Instant lastSwitch = Instant.EPOCH;

    public HaCacheRouter(
            HaCacheProperties properties,
            CacheNodeFactory nodeFactory,
            CacheProvisioner provisioner,
            MeterRegistry meterRegistry,
            @Qualifier("cacheProvisioningExecutor") TaskExecutor provisioningExecutor) {
        this.properties = properties;
        this.nodeFactory = nodeFactory;
        this.provisioner = provisioner;
        this.meterRegistry = meterRegistry;
        this.provisioningExecutor = provisioningExecutor;
        this.failovers = Counter.builder("oci.cache.failovers")
                .description("Quantidade de trocas do endpoint regional ativo")
                .register(meterRegistry);
        meterRegistry.gauge("oci.cache.configured.nodes", nodes, List::size);
    }

    @PostConstruct
    public void initialize() {
        List<RuntimeException> errors = new ArrayList<>();
        for (HaCacheProperties.Region region : properties.getRegions()) {
            if (!region.isConfigured()) {
                continue;
            }
            try {
                nodes.add(nodeFactory.create(region));
            } catch (RuntimeException exception) {
                errors.add(exception);
                log.error("Não foi possível inicializar o endpoint {}", region.getName(), exception);
            }
        }
        nodes.stream().filter(this::isHealthy).findFirst().ifPresent(active::set);
        if (active.get() == null && !properties.getProvisioning().isEnabled()) {
            Throwable cause = errors.isEmpty() ? null : errors.get(0);
            throw new CacheUnavailableException("Nenhum endpoint OCI Cache saudável na inicialização", cause);
        }
        if (active.get() == null) {
            requestProvisioning();
        } else {
            log.info("OCI Cache ativo: {} ({})", active.get().name(), active.get().region());
        }
    }

    public String get(String key) {
        return execute("get", node -> node.get(key));
    }

    public String getPrimary(String key) {
        return execute("get_primary", node -> node.getPrimary(key));
    }

    public void set(String key, String value, Duration ttl) {
        execute("set", node -> {
            node.set(key, value, ttl);
            return null;
        });
    }

    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return execute("set_if_absent", node -> node.setIfAbsent(key, value, ttl));
    }

    public void delete(String key) {
        execute("delete", node -> {
            node.delete(key);
            return null;
        });
    }

    public long increment(String key) {
        return execute("increment", node -> node.increment(key));
    }

    public String publish(String stream, String eventId, String payload, Duration deduplicationTtl) {
        return execute("stream_publish", node -> node.publishIdempotently(
                stream, eventId, payload, deduplicationTtl));
    }

    public void ensureConsumerGroup(String stream, String group) {
        execute("stream_group_create", node -> {
            node.ensureConsumerGroup(stream, group);
            return null;
        });
    }

    public List<CacheMessage> readGroup(
            String stream,
            String group,
            String consumer,
            int count,
            Duration block) {
        return execute("stream_read", node -> node.readGroup(stream, group, consumer, count, block));
    }

    public List<CacheMessage> claimPending(
            String stream,
            String group,
            String consumer,
            int count,
            Duration minIdle) {
        return execute("stream_claim", node -> node.claimPending(
                stream, group, consumer, count, minIdle));
    }

    public void acknowledge(String stream, String group, String messageId) {
        execute("stream_ack", node -> {
            node.acknowledge(stream, group, messageId);
            return null;
        });
    }

    public long pendingCount(String stream, String group) {
        return execute("stream_pending", node -> node.pendingCount(stream, group));
    }

    public long recordProcessingFailure(
            String stream,
            String messageId,
            String attemptToken,
            Duration ttl) {
        return execute("stream_failure_record",
                node -> node.recordProcessingFailure(stream, messageId, attemptToken, ttl));
    }

    public String deadLetter(
            String stream,
            String deadLetterStream,
            String group,
            CacheMessage message,
            String reason,
            long maxLength,
            Duration deduplicationTtl) {
        return execute("stream_dead_letter", node -> node.deadLetter(
                stream, deadLetterStream, group, message, reason, maxLength, deduplicationTtl));
    }

    private <T> T execute(String operationName, Function<CacheNode, T> operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            return executeWithRetry(operation);
        } catch (RuntimeException exception) {
            outcome = "error";
            throw exception;
        } finally {
            CacheNode current = active.get();
            sample.stop(Timer.builder("oci.cache.operation")
                    .description("Latência das operações lógicas no OCI Cache, incluindo retry")
                    .tag("operation", operationName)
                    .tag("outcome", outcome)
                    .tag("region", current == null ? "none" : current.region())
                    .register(meterRegistry));
        }
    }

    private <T> T executeWithRetry(Function<CacheNode, T> operation) {
        RuntimeException lastFailure = null;
        int attempts = properties.getFailover().getCommandAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            CacheNode current = active.get();
            if (current == null) {
                requestProvisioning();
                lastFailure = new IllegalStateException("Nenhum endpoint ativo");
            } else {
                try {
                    T result = executeProtected(current, operation);
                    consecutiveFailures.set(0);
                    return result;
                } catch (RedisCommandExecutionException exception) {
                    // Erros de comando (ACL, WRONGTYPE, CROSSSLOT, payload) não
                    // indicam indisponibilidade regional e não devem causar failover.
                    throw exception;
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                    int failures = consecutiveFailures.incrementAndGet();
                    log.warn("Comando falhou em {} (tentativa {}/{}, falhas consecutivas {})",
                            current.name(), attempt, attempts, failures);
                    if (failures >= properties.getFailover().getFailureThreshold()) {
                        switchToHealthyStandby(current);
                    }
                }
            }
            if (attempt < attempts) {
                backoff(attempt);
            }
        }
        throw new CacheUnavailableException("OCI Cache indisponível após " + attempts + " tentativas",
                lastFailure);
    }

    @Scheduled(fixedDelayString = "${app.cache.failover.health-interval:5s}")
    void monitor() {
        CacheNode current = active.get();
        if (current == null) {
            requestProvisioning();
            return;
        }
        if (isHealthy(current)) {
            consecutiveFailures.set(0);
            maybeFailback(current);
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= properties.getFailover().getFailureThreshold()) {
            switchToHealthyStandby(current);
        }
    }

    private synchronized void switchToHealthyStandby(CacheNode failed) {
        if (active.get() != failed) {
            return;
        }
        OptionalNode candidate = nodes.stream()
                .filter(node -> node != failed)
                .filter(this::isHealthy)
                .findFirst()
                .map(OptionalNode::new)
                .orElse(OptionalNode.empty());
        if (candidate.node() == null) {
            log.error("Nenhum standby saudável para substituir {}", failed.name());
            requestProvisioning();
            return;
        }
        active.set(candidate.node());
        consecutiveFailures.set(0);
        lastSwitch = Instant.now();
        failovers.increment();
        log.error("FAILOVER: OCI Cache ativo mudou de {} para {} ({})",
                failed.name(), candidate.node().name(), candidate.node().region());
    }

    private void maybeFailback(CacheNode current) {
        if (!properties.getFailover().isAutomaticFailback()
                || nodes.isEmpty()
                || current == nodes.get(0)
                || Instant.now().isBefore(lastSwitch.plus(properties.getFailover().getCooldown()))) {
            return;
        }
        CacheNode preferred = nodes.get(0);
        if (isHealthy(preferred)) {
            synchronized (this) {
                if (active.get() == current) {
                    active.set(preferred);
                    lastSwitch = Instant.now();
                    failovers.increment();
                    log.warn("FAILBACK: OCI Cache retornou para {}", preferred.name());
                }
            }
        }
    }

    private void requestProvisioning() {
        if (!properties.getProvisioning().isEnabled()
                || !provisioning.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                provisioner.provisionOrFindStandby().ifPresent(region -> {
                    CacheNode node = nodeFactory.create(region);
                    nodes.add(node);
                    if (isHealthy(node)) {
                        CacheNode previous = active.getAndSet(node);
                        consecutiveFailures.set(0);
                        lastSwitch = Instant.now();
                        failovers.increment();
                        log.error("OCI Cache provisionado e ativado em {} (anterior: {})",
                                node.region(), previous == null ? "nenhum" : previous.name());
                    }
                });
            } catch (RuntimeException exception) {
                log.error("Provisionamento de DR falhou", exception);
            } finally {
                provisioning.set(false);
            }
        }, provisioningExecutor);
    }

    private boolean isHealthy(CacheNode node) {
        try {
            return node.ping();
        } catch (RuntimeException exception) {
            log.debug("Health check falhou para {}", node.name(), exception);
            return false;
        }
    }

    private <T> T executeProtected(CacheNode node, Function<CacheNode, T> operation) {
        if (!properties.getFailover().isCircuitBreakerEnabled()) {
            return operation.apply(node);
        }
        return circuitBreakerFor(node).executeSupplier(() -> operation.apply(node));
    }

    private CircuitBreaker circuitBreakerFor(CacheNode node) {
        return circuitBreakers.computeIfAbsent(node, ignored -> {
            HaCacheProperties.Failover config = properties.getFailover();
            CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                    .failureRateThreshold(config.getCircuitBreakerFailureRateThreshold())
                    .slidingWindowSize(config.getCircuitBreakerSlidingWindowSize())
                    .minimumNumberOfCalls(config.getCircuitBreakerMinimumCalls())
                    .permittedNumberOfCallsInHalfOpenState(config.getCircuitBreakerHalfOpenCalls())
                    .waitDurationInOpenState(config.getCircuitBreakerOpenDuration())
                    .ignoreExceptions(RedisCommandExecutionException.class)
                    .build();
            return CircuitBreaker.of("oci-cache-" + node.name(), breakerConfig);
        });
    }

    private void backoff(int attempt) {
        HaCacheProperties.Failover config = properties.getFailover();
        long millis = calculateBackoffMillis(
                config.getRetryBackoff().toMillis(),
                config.getRetryMaxBackoff().toMillis(),
                config.getRetryJitter(),
                attempt,
                ThreadLocalRandom.current().nextDouble());
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CacheUnavailableException("Retry interrompido", exception);
        }
    }

    static long calculateBackoffMillis(
            long baseMillis,
            long maxMillis,
            double jitter,
            int attempt,
            double random) {
        if (baseMillis <= 0 || maxMillis <= 0) {
            return 0;
        }
        int shift = Math.min(Math.max(attempt - 1, 0), 30);
        long exponential;
        try {
            exponential = Math.multiplyExact(baseMillis, 1L << shift);
        } catch (ArithmeticException exception) {
            exponential = Long.MAX_VALUE;
        }
        long capped = Math.min(exponential, maxMillis);
        double normalizedRandom = Math.max(0.0, Math.min(1.0, random));
        double multiplier = (1.0 - jitter) + (2.0 * jitter * normalizedRandom);
        return Math.max(0L, Math.min(maxMillis, Math.round(capped * multiplier)));
    }

    public Map<String, Object> status() {
        CacheNode current = active.get();
        return Map.of(
                "status", current == null ? "DOWN" : "UP",
                "activeNode", current == null ? "none" : current.name(),
                "activeRegion", current == null ? "none" : current.region(),
                "configuredNodes", nodes.size(),
                "consecutiveFailures", consecutiveFailures.get(),
                "provisioning", provisioning.get(),
                "circuitBreaker", current == null || !properties.getFailover().isCircuitBreakerEnabled()
                        ? "DISABLED"
                        : circuitBreakerFor(current).getState().name());
    }

    @PreDestroy
    public void close() {
        nodes.forEach(CacheNode::close);
    }

    private record OptionalNode(CacheNode node) {
        private static OptionalNode empty() {
            return new OptionalNode(null);
        }
    }
}
