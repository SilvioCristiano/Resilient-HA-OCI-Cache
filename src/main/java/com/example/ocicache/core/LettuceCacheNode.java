package com.example.ocicache.core;

import com.example.ocicache.config.HaCacheProperties;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.Consumer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ReadFrom;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

final class LettuceCacheNode implements CacheNode {

    private static final String IDEMPOTENT_XADD = """
            local previous = redis.call('GET', KEYS[2])
            if previous then return previous end
            local id = redis.call('XADD', KEYS[1], '*',
              'eventId', ARGV[1], 'payload', ARGV[2], 'producedAt', ARGV[3])
            redis.call('SET', KEYS[2], id, 'PX', ARGV[4])
            return id
            """;
    private static final String RECORD_PROCESSING_FAILURE = """
            local existing = redis.call('GET', KEYS[2])
            if existing then return tonumber(existing) end
            local attempts = redis.call('INCR', KEYS[1])
            redis.call('SET', KEYS[2], attempts, 'PX', ARGV[1])
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return attempts
            """;
    private static final String MOVE_TO_DEAD_LETTER = """
            local previous = redis.call('GET', KEYS[4])
            if previous then
              redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
              return previous
            end
            local dlqId = redis.call('XADD', KEYS[2],
              'MAXLEN', '~', ARGV[8], '*',
              'sourceStream', KEYS[1],
              'sourceId', ARGV[2],
              'consumerGroup', ARGV[1],
              'eventId', ARGV[3],
              'payload', ARGV[4],
              'producedAt', ARGV[5],
              'failedAt', ARGV[6],
              'reason', ARGV[7])
            redis.call('SET', KEYS[4], dlqId, 'PX', ARGV[9])
            redis.call('XACK', KEYS[1], ARGV[1], ARGV[2])
            redis.call('DEL', KEYS[3])
            return dlqId
            """;

    private final HaCacheProperties.Region region;
    private final RedisClient standaloneClient;
    private final StatefulRedisConnection<String, String> standaloneConnection;
    private final StatefulRedisConnection<String, String> standaloneReadConnection;
    private final StatefulRedisConnection<String, String> standaloneBlockingConnection;
    private final RedisClusterClient clusterClient;
    private final StatefulRedisClusterConnection<String, String> clusterConnection;
    private final StatefulRedisClusterConnection<String, String> clusterPrimaryConnection;
    private final StatefulRedisClusterConnection<String, String> clusterBlockingConnection;

    LettuceCacheNode(
            HaCacheProperties.Region region,
            HaCacheProperties.Failover failover) {
        if (!region.isConfigured()) {
            throw new IllegalArgumentException("A região " + region.getName() + " não possui FQDN configurado");
        }
        this.region = region;
        List<RedisURI> uris = region.getHosts().stream()
                .filter(host -> host != null && !host.isBlank())
                .map(host -> redisUri(region, host, failover.getCommandTimeout()))
                .toList();

        SocketOptions sockets = SocketOptions.builder()
                .connectTimeout(failover.getConnectTimeout())
                .keepAlive(true)
                .tcpNoDelay(true)
                .build();

        if (region.getMode() == HaCacheProperties.Mode.SHARDED) {
            this.clusterClient = RedisClusterClient.create(uris);
            this.clusterClient.setOptions(ClusterClientOptions.builder()
                    .autoReconnect(true)
                    .validateClusterNodeMembership(true)
                    .maxRedirects(5)
                    .socketOptions(sockets)
                    .build());
            this.clusterConnection = clusterClient.connect();
            this.clusterPrimaryConnection = clusterClient.connect();
            if (region.isReadFromReplicas()) {
                this.clusterConnection.setReadFrom(ReadFrom.REPLICA_PREFERRED);
            }
            this.clusterBlockingConnection = clusterClient.connect();
            this.standaloneClient = null;
            this.standaloneConnection = null;
            this.standaloneReadConnection = null;
            this.standaloneBlockingConnection = null;
        } else {
            this.standaloneClient = RedisClient.create(uris.get(0));
            this.standaloneClient.setOptions(ClientOptions.builder()
                    .autoReconnect(true)
                    .socketOptions(sockets)
                    .build());
            this.standaloneConnection = standaloneClient.connect();
            this.standaloneReadConnection = region.isReadFromReplicas()
                    && region.getReadHost() != null
                    && !region.getReadHost().isBlank()
                    ? standaloneClient.connect(redisUri(
                            region, region.getReadHost(), failover.getCommandTimeout()))
                    : standaloneConnection;
            this.standaloneBlockingConnection = standaloneClient.connect();
            this.clusterClient = null;
            this.clusterConnection = null;
            this.clusterPrimaryConnection = null;
            this.clusterBlockingConnection = null;
        }
    }

    private static RedisURI redisUri(
            HaCacheProperties.Region region,
            String host,
            Duration timeout) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(region.getPort())
                .withTimeout(timeout)
                .withSsl(region.isTls())
                .withVerifyPeer(region.isTls());
        if (region.getUsername() != null && !region.getUsername().isBlank()) {
            builder.withAuthentication(
                    region.getUsername(),
                    Objects.requireNonNullElse(region.getPassword(), "").toCharArray());
        } else if (region.getPassword() != null && !region.getPassword().isBlank()) {
            builder.withPassword(region.getPassword().toCharArray());
        }
        return builder.build();
    }

    @Override
    public String name() {
        return region.getName();
    }

    @Override
    public String region() {
        return region.getOciRegion();
    }

    @Override
    public boolean ping() {
        return "PONG".equals(command(RedisCommands::ping, RedisAdvancedClusterCommands::ping));
    }

    @Override
    public String get(String key) {
        if (standaloneReadConnection != null) {
            return standaloneReadConnection.sync().get(key);
        }
        return clusterConnection.sync().get(key);
    }

    @Override
    public String getPrimary(String key) {
        if (standaloneConnection != null) {
            return standaloneConnection.sync().get(key);
        }
        return clusterPrimaryConnection.sync().get(key);
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        SetArgs args = SetArgs.Builder.px(ttl);
        command(commands -> commands.set(key, value, args), commands -> commands.set(key, value, args));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        SetArgs args = SetArgs.Builder.nx().px(ttl);
        return "OK".equals(command(
                commands -> commands.set(key, value, args),
                commands -> commands.set(key, value, args)));
    }

    @Override
    public void delete(String key) {
        command(commands -> commands.del(key), commands -> commands.del(key));
    }

    @Override
    public long increment(String key) {
        return command(commands -> commands.incr(key), commands -> commands.incr(key));
    }

    @Override
    public String publishIdempotently(
            String stream,
            String eventId,
            String payload,
            Duration deduplicationTtl) {
        String deduplicationKey = stream + ":dedupe:" + eventId;
        String producedAt = Instant.now().toString();
        String ttlMillis = Long.toString(deduplicationTtl.toMillis());
        return command(
                commands -> commands.eval(
                        IDEMPOTENT_XADD,
                        ScriptOutputType.VALUE,
                        new String[]{stream, deduplicationKey},
                        eventId, payload, producedAt, ttlMillis),
                commands -> commands.eval(
                        IDEMPOTENT_XADD,
                        ScriptOutputType.VALUE,
                        new String[]{stream, deduplicationKey},
                        eventId, payload, producedAt, ttlMillis));
    }

    @Override
    public void ensureConsumerGroup(String stream, String group) {
        try {
            command(
                    commands -> commands.xgroupCreate(
                            XReadArgs.StreamOffset.from(stream, "0-0"),
                            group,
                            XGroupCreateArgs.Builder.mkstream()),
                    commands -> commands.xgroupCreate(
                            XReadArgs.StreamOffset.from(stream, "0-0"),
                            group,
                            XGroupCreateArgs.Builder.mkstream()));
        } catch (RedisCommandExecutionException exception) {
            if (!exception.getMessage().contains("BUSYGROUP")) {
                throw exception;
            }
        }
    }

    @Override
    public List<CacheMessage> readGroup(
            String stream,
            String group,
            String consumer,
            int count,
            Duration block) {
        XReadArgs args = XReadArgs.Builder.count(count).block(block);
        List<StreamMessage<String, String>> messages = blockingCommand(
                commands -> commands.xreadgroup(
                        Consumer.from(group, consumer),
                        args,
                        XReadArgs.StreamOffset.lastConsumed(stream)),
                commands -> commands.xreadgroup(
                        Consumer.from(group, consumer),
                        args,
                        XReadArgs.StreamOffset.lastConsumed(stream)));
        return messages.stream().map(this::toMessage).collect(Collectors.toList());
    }

    @Override
    public List<CacheMessage> claimPending(
            String stream,
            String group,
            String consumer,
            int count,
            Duration minIdle) {
        XAutoClaimArgs<String> args = new XAutoClaimArgs<String>()
                .consumer(Consumer.from(group, consumer))
                .minIdleTime(minIdle)
                .startId("0-0")
                .count(count);
        List<StreamMessage<String, String>> messages = command(
                commands -> commands.xautoclaim(stream, args).getMessages(),
                commands -> commands.xautoclaim(stream, args).getMessages());
        return messages.stream().map(this::toMessage).collect(Collectors.toList());
    }

    @Override
    public long pendingCount(String stream, String group) {
        return command(
                commands -> commands.xpending(stream, group).getCount(),
                commands -> commands.xpending(stream, group).getCount());
    }

    @Override
    public long recordProcessingFailure(
            String stream,
            String messageId,
            String attemptToken,
            Duration ttl) {
        String failureKey = failureKey(stream, messageId);
        String attemptKey = failureKey + ":attempt:" + attemptToken;
        Number attempts = command(
                commands -> commands.eval(
                        RECORD_PROCESSING_FAILURE,
                        ScriptOutputType.INTEGER,
                        new String[]{failureKey, attemptKey},
                        Long.toString(ttl.toMillis())),
                commands -> commands.eval(
                        RECORD_PROCESSING_FAILURE,
                        ScriptOutputType.INTEGER,
                        new String[]{failureKey, attemptKey},
                        Long.toString(ttl.toMillis())));
        return attempts.longValue();
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
        String failureKey = failureKey(stream, message.redisId());
        String deadLetterMarkerKey = failureKey + ":dead-letter";
        String producedAt = message.producedAt() == null
                ? Instant.EPOCH.toString()
                : message.producedAt().toString();
        return command(
                commands -> commands.eval(
                        MOVE_TO_DEAD_LETTER,
                        ScriptOutputType.VALUE,
                        new String[]{stream, deadLetterStream, failureKey, deadLetterMarkerKey},
                        group,
                        message.redisId(),
                        Objects.requireNonNullElse(message.eventId(), ""),
                        Objects.requireNonNullElse(message.payload(), ""),
                        producedAt,
                        Instant.now().toString(),
                        reason,
                        Long.toString(maxLength),
                        Long.toString(deduplicationTtl.toMillis())),
                commands -> commands.eval(
                        MOVE_TO_DEAD_LETTER,
                        ScriptOutputType.VALUE,
                        new String[]{stream, deadLetterStream, failureKey, deadLetterMarkerKey},
                        group,
                        message.redisId(),
                        Objects.requireNonNullElse(message.eventId(), ""),
                        Objects.requireNonNullElse(message.payload(), ""),
                        producedAt,
                        Instant.now().toString(),
                        reason,
                        Long.toString(maxLength),
                        Long.toString(deduplicationTtl.toMillis())));
    }

    private String failureKey(String stream, String messageId) {
        return stream + ":failures:" + messageId;
    }

    private CacheMessage toMessage(StreamMessage<String, String> message) {
        Map<String, String> body = message.getBody();
        String timestamp = body.get("producedAt");
        return new CacheMessage(
                message.getId(),
                body.get("eventId"),
                body.get("payload"),
                timestamp == null ? Instant.EPOCH : Instant.parse(timestamp));
    }

    @Override
    public void acknowledge(String stream, String group, String messageId) {
        command(
                commands -> commands.xack(stream, group, messageId),
                commands -> commands.xack(stream, group, messageId));
    }

    private <T> T command(
            Function<RedisCommands<String, String>, T> standalone,
            Function<RedisAdvancedClusterCommands<String, String>, T> cluster) {
        if (standaloneConnection != null) {
            return standalone.apply(standaloneConnection.sync());
        }
        return cluster.apply(clusterConnection.sync());
    }

    private <T> T blockingCommand(
            Function<RedisCommands<String, String>, T> standalone,
            Function<RedisAdvancedClusterCommands<String, String>, T> cluster) {
        if (standaloneBlockingConnection != null) {
            return standalone.apply(standaloneBlockingConnection.sync());
        }
        return cluster.apply(clusterBlockingConnection.sync());
    }

    @Override
    public void close() {
        if (standaloneConnection != null) {
            standaloneBlockingConnection.close();
            if (standaloneReadConnection != standaloneConnection) {
                standaloneReadConnection.close();
            }
            standaloneConnection.close();
            standaloneClient.shutdown();
        }
        if (clusterConnection != null) {
            clusterBlockingConnection.close();
            clusterPrimaryConnection.close();
            clusterConnection.close();
            clusterClient.shutdown();
        }
    }
}
