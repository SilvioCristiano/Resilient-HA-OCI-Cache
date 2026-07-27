package com.example.ocicache.stream;

import com.example.ocicache.config.HaCacheProperties;
import com.example.ocicache.core.CacheMessage;
import com.example.ocicache.core.CacheUnavailableException;
import com.example.ocicache.core.HaCacheRouter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumer.class);

    private final HaCacheRouter cache;
    private final HaCacheProperties properties;
    private final BusinessMessageHandler handler;
    private final Counter processed;
    private final Counter failed;
    private final Counter reclaimed;
    private final Counter deadLettered;
    private final Timer processingTimer;
    private final AtomicBoolean groupReady = new AtomicBoolean();
    private final AtomicLong pendingEntries = new AtomicLong();

    public StreamConsumer(
            HaCacheRouter cache,
            HaCacheProperties properties,
            BusinessMessageHandler handler,
            MeterRegistry meterRegistry) {
        this.cache = cache;
        this.properties = properties;
        this.handler = handler;
        this.processed = meterRegistry.counter("oci.cache.stream.processed");
        this.failed = meterRegistry.counter("oci.cache.stream.failed");
        this.reclaimed = meterRegistry.counter("oci.cache.stream.reclaimed");
        this.deadLettered = meterRegistry.counter("oci.cache.stream.dead_lettered");
        this.processingTimer = meterRegistry.timer("oci.cache.stream.processing");
        meterRegistry.gauge("oci.cache.stream.pending", pendingEntries);
    }

    @Scheduled(fixedDelayString = "${app.cache.stream.pel-monitor-interval:15s}")
    public void monitorPendingEntries() {
        HaCacheProperties.Stream stream = properties.getStream();
        if (!stream.isConsumerEnabled() || !groupReady.get()) {
            return;
        }
        try {
            pendingEntries.set(cache.pendingCount(stream.getKey(), stream.getConsumerGroup()));
        } catch (RuntimeException exception) {
            groupReady.set(false);
            log.warn("Não foi possível consultar a PEL; o grupo será revalidado", exception);
        }
    }

    @Scheduled(fixedDelayString = "${app.cache.stream.consumer-delay:1s}")
    public void poll() {
        HaCacheProperties.Stream stream = properties.getStream();
        if (!stream.isConsumerEnabled()) {
            return;
        }
        try {
            if (groupReady.compareAndSet(false, true)) {
                try {
                    cache.ensureConsumerGroup(stream.getKey(), stream.getConsumerGroup());
                } catch (RuntimeException exception) {
                    groupReady.set(false);
                    throw exception;
                }
            }
            List<CacheMessage> pending = cache.claimPending(
                    stream.getKey(),
                    stream.getConsumerGroup(),
                    stream.getConsumerName(),
                    stream.getBatchSize(),
                    stream.getPendingMinIdle());
            reclaimed.increment(pending.size());
            for (CacheMessage message : pending) {
                processAndAcknowledge(stream, message);
            }
            List<CacheMessage> messages = cache.readGroup(
                    stream.getKey(),
                    stream.getConsumerGroup(),
                    stream.getConsumerName(),
                    stream.getBatchSize(),
                    stream.getPollTimeout());
            for (CacheMessage message : messages) {
                processAndAcknowledge(stream, message);
            }
        } catch (CacheUnavailableException exception) {
            groupReady.set(false);
            log.warn("Consumer aguardando recuperação do OCI Cache: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            // Inclui NOGROUP após troca para um cluster regional recém-criado.
            groupReady.set(false);
            log.error("Consumer será reinicializado no próximo polling", exception);
        }
    }

    private void processAndAcknowledge(
            HaCacheProperties.Stream stream,
            CacheMessage message) {
        try {
            processingTimer.record(() -> handler.handle(message));
        } catch (RuntimeException exception) {
            failed.increment();
            handleFailure(stream, message, exception);
            return;
        }
        // Falha de transporte no ACK não é falha de negócio e não consome uma
        // tentativa. A mensagem será reentregue e o handler deve ser idempotente.
        cache.acknowledge(stream.getKey(), stream.getConsumerGroup(), message.redisId());
        processed.increment();
    }

    private void handleFailure(
            HaCacheProperties.Stream stream,
            CacheMessage message,
            RuntimeException exception) {
        boolean permanent = hasPermanentCause(exception);
        long attempts = permanent
                ? stream.getMaxProcessingAttempts()
                : cache.recordProcessingFailure(
                        stream.getKey(),
                        message.redisId(),
                        UUID.randomUUID().toString(),
                        stream.getFailureTrackingTtl());
        if (attempts < stream.getMaxProcessingAttempts()) {
            log.warn("Evento {} não recebeu ACK (tentativa de processamento {}/{})",
                    message.eventId(), attempts, stream.getMaxProcessingAttempts(), exception);
            return;
        }

        String reason = failureReason(exception);
        String dlqId = cache.deadLetter(
                stream.getKey(),
                stream.getDeadLetterKey(),
                stream.getConsumerGroup(),
                message,
                reason,
                stream.getDeadLetterMaxLength(),
                stream.getFailureTrackingTtl());
        deadLettered.increment();
        log.error("Evento {} enviado à Dead Letter Stream {} como {} após {} tentativa(s)",
                message.eventId(), stream.getDeadLetterKey(), dlqId, attempts, exception);
    }

    private boolean hasPermanentCause(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof PermanentMessageProcessingException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String failureReason(Throwable exception) {
        String type = exception.getClass().getSimpleName();
        String message = exception.getMessage();
        String value = message == null || message.isBlank() ? type : type + ": " + message;
        return value.length() <= 1_000 ? value : value.substring(0, 1_000);
    }
}
