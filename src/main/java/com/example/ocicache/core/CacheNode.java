package com.example.ocicache.core;

import java.time.Duration;
import java.util.List;

public interface CacheNode extends AutoCloseable {

    String name();

    String region();

    boolean ping();

    String get(String key);

    String getPrimary(String key);

    void set(String key, String value, Duration ttl);

    boolean setIfAbsent(String key, String value, Duration ttl);

    void delete(String key);

    long increment(String key);

    String publishIdempotently(
            String stream,
            String eventId,
            String payload,
            Duration deduplicationTtl);

    void ensureConsumerGroup(String stream, String group);

    List<CacheMessage> readGroup(
            String stream,
            String group,
            String consumer,
            int count,
            Duration block);

    List<CacheMessage> claimPending(
            String stream,
            String group,
            String consumer,
            int count,
            Duration minIdle);

    long pendingCount(String stream, String group);

    long recordProcessingFailure(
            String stream,
            String messageId,
            String attemptToken,
            Duration ttl);

    String deadLetter(
            String stream,
            String deadLetterStream,
            String group,
            CacheMessage message,
            String reason,
            long maxLength,
            Duration deduplicationTtl);

    void acknowledge(String stream, String group, String messageId);

    @Override
    void close();
}
