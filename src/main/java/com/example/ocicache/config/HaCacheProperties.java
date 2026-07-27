package com.example.ocicache.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.cache")
public class HaCacheProperties {

    @Valid
    @NotEmpty
    private List<Region> regions = new ArrayList<>();

    @Valid
    private final Failover failover = new Failover();

    @Valid
    private final Stream stream = new Stream();

    @Valid
    private final SpringCache spring = new SpringCache();

    @Valid
    private final Provisioning provisioning = new Provisioning();

    public List<Region> getRegions() {
        return regions;
    }

    public void setRegions(List<Region> regions) {
        this.regions = regions;
    }

    public Failover getFailover() {
        return failover;
    }

    public Stream getStream() {
        return stream;
    }

    public SpringCache getSpring() {
        return spring;
    }

    public Provisioning getProvisioning() {
        return provisioning;
    }

    @AssertTrue(message = "stream.key e dead-letter-key devem usar a mesma hash tag no modo SHARDED")
    public boolean isStreamKeyCompatibleWithClusterMode() {
        boolean sharded = regions != null && regions.stream()
                .anyMatch(region -> region.getMode() == Mode.SHARDED);
        String key = stream.getKey();
        String deadLetterKey = stream.getDeadLetterKey();
        return !sharded || (hashTag(key) != null && hashTag(key).equals(hashTag(deadLetterKey)));
    }

    private String hashTag(String key) {
        if (key == null) {
            return null;
        }
        int start = key.indexOf('{');
        int end = start < 0 ? -1 : key.indexOf('}', start + 1);
        return start >= 0 && end > start + 1 ? key.substring(start + 1, end) : null;
    }

    public enum Mode {
        NON_SHARDED,
        SHARDED
    }

    public static class Region {
        @NotBlank
        private String name;
        @NotBlank
        private String ociRegion;
        private List<String> hosts = new ArrayList<>();
        @Min(1)
        private int port = 6379;
        @NotNull
        private Mode mode = Mode.NON_SHARDED;
        private boolean tls = true;
        private String username;
        private String password;
        private String clusterOcid;
        private String readHost;
        private boolean readFromReplicas;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getOciRegion() {
            return ociRegion;
        }

        public void setOciRegion(String ociRegion) {
            this.ociRegion = ociRegion;
        }

        public List<String> getHosts() {
            return hosts;
        }

        public void setHosts(List<String> hosts) {
            this.hosts = hosts;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public boolean isTls() {
            return tls;
        }

        public void setTls(boolean tls) {
            this.tls = tls;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getClusterOcid() {
            return clusterOcid;
        }

        public void setClusterOcid(String clusterOcid) {
            this.clusterOcid = clusterOcid;
        }

        public String getReadHost() {
            return readHost;
        }

        public void setReadHost(String readHost) {
            this.readHost = readHost;
        }

        public boolean isReadFromReplicas() {
            return readFromReplicas;
        }

        public void setReadFromReplicas(boolean readFromReplicas) {
            this.readFromReplicas = readFromReplicas;
        }

        public boolean isConfigured() {
            return hosts != null && hosts.stream().anyMatch(
                    host -> host != null && !host.isBlank());
        }
    }

    public static class SpringCache {
        private boolean enabled = true;
        @NotBlank
        private String namespace = "resilient-ha-oci-cache:prod";
        @NotNull
        private Duration defaultTtl = Duration.ofMinutes(10);
        @NotEmpty
        private List<String> cacheNames = new ArrayList<>(List.of("customers", "products"));
        private Map<String, Duration> ttlByCache = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public Duration getDefaultTtl() {
            return defaultTtl;
        }

        public void setDefaultTtl(Duration defaultTtl) {
            this.defaultTtl = defaultTtl;
        }

        public List<String> getCacheNames() {
            return cacheNames;
        }

        public void setCacheNames(List<String> cacheNames) {
            this.cacheNames = cacheNames;
        }

        public Map<String, Duration> getTtlByCache() {
            return ttlByCache;
        }

        public void setTtlByCache(Map<String, Duration> ttlByCache) {
            this.ttlByCache = ttlByCache;
        }

        public Duration ttlFor(String cacheName) {
            return ttlByCache.getOrDefault(cacheName, defaultTtl);
        }

        @AssertTrue(message = "Os TTLs do Spring Cache devem ser maiores que zero")
        public boolean isTtlConfigurationValid() {
            return defaultTtl != null
                    && !defaultTtl.isNegative()
                    && !defaultTtl.isZero()
                    && ttlByCache.values().stream().allMatch(
                            ttl -> ttl != null && !ttl.isNegative() && !ttl.isZero());
        }
    }

    public static class Failover {
        @Min(1)
        private int failureThreshold = 3;
        @Min(1)
        @Max(5)
        private int commandAttempts = 3;
        @NotNull
        private Duration commandTimeout = Duration.ofSeconds(3);
        @NotNull
        private Duration connectTimeout = Duration.ofSeconds(3);
        @NotNull
        private Duration retryBackoff = Duration.ofMillis(500);
        @NotNull
        private Duration retryMaxBackoff = Duration.ofSeconds(5);
        @DecimalMin("0.0")
        @DecimalMax("1.0")
        private double retryJitter = 0.5;
        private boolean circuitBreakerEnabled = true;
        @Min(1)
        @Max(100)
        private int circuitBreakerFailureRateThreshold = 50;
        @Min(2)
        private int circuitBreakerSlidingWindowSize = 10;
        @Min(1)
        private int circuitBreakerMinimumCalls = 5;
        @Min(1)
        private int circuitBreakerHalfOpenCalls = 2;
        @NotNull
        private Duration circuitBreakerOpenDuration = Duration.ofSeconds(10);
        @NotNull
        private Duration healthInterval = Duration.ofSeconds(5);
        @NotNull
        private Duration cooldown = Duration.ofMinutes(2);
        private boolean automaticFailback;

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public int getCommandAttempts() {
            return commandAttempts;
        }

        public void setCommandAttempts(int commandAttempts) {
            this.commandAttempts = commandAttempts;
        }

        public Duration getCommandTimeout() {
            return commandTimeout;
        }

        public void setCommandTimeout(Duration commandTimeout) {
            this.commandTimeout = commandTimeout;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getRetryBackoff() {
            return retryBackoff;
        }

        public void setRetryBackoff(Duration retryBackoff) {
            this.retryBackoff = retryBackoff;
        }

        public Duration getRetryMaxBackoff() {
            return retryMaxBackoff;
        }

        public void setRetryMaxBackoff(Duration retryMaxBackoff) {
            this.retryMaxBackoff = retryMaxBackoff;
        }

        public double getRetryJitter() {
            return retryJitter;
        }

        public void setRetryJitter(double retryJitter) {
            this.retryJitter = retryJitter;
        }

        public boolean isCircuitBreakerEnabled() {
            return circuitBreakerEnabled;
        }

        public void setCircuitBreakerEnabled(boolean circuitBreakerEnabled) {
            this.circuitBreakerEnabled = circuitBreakerEnabled;
        }

        public int getCircuitBreakerFailureRateThreshold() {
            return circuitBreakerFailureRateThreshold;
        }

        public void setCircuitBreakerFailureRateThreshold(int value) {
            this.circuitBreakerFailureRateThreshold = value;
        }

        public int getCircuitBreakerSlidingWindowSize() {
            return circuitBreakerSlidingWindowSize;
        }

        public void setCircuitBreakerSlidingWindowSize(int value) {
            this.circuitBreakerSlidingWindowSize = value;
        }

        public int getCircuitBreakerMinimumCalls() {
            return circuitBreakerMinimumCalls;
        }

        public void setCircuitBreakerMinimumCalls(int value) {
            this.circuitBreakerMinimumCalls = value;
        }

        public int getCircuitBreakerHalfOpenCalls() {
            return circuitBreakerHalfOpenCalls;
        }

        public void setCircuitBreakerHalfOpenCalls(int value) {
            this.circuitBreakerHalfOpenCalls = value;
        }

        public Duration getCircuitBreakerOpenDuration() {
            return circuitBreakerOpenDuration;
        }

        public void setCircuitBreakerOpenDuration(Duration value) {
            this.circuitBreakerOpenDuration = value;
        }

        public Duration getHealthInterval() {
            return healthInterval;
        }

        public void setHealthInterval(Duration healthInterval) {
            this.healthInterval = healthInterval;
        }

        public Duration getCooldown() {
            return cooldown;
        }

        public void setCooldown(Duration cooldown) {
            this.cooldown = cooldown;
        }

        public boolean isAutomaticFailback() {
            return automaticFailback;
        }

        public void setAutomaticFailback(boolean automaticFailback) {
            this.automaticFailback = automaticFailback;
        }
    }

    public static class Stream {
        @NotBlank
        private String key = "{orders}:stream";
        @NotBlank
        private String consumerGroup = "orders-service";
        @NotBlank
        private String consumerName = "${HOSTNAME:local}";
        @Min(1)
        private int batchSize = 10;
        @NotNull
        private Duration pollTimeout = Duration.ofSeconds(2);
        @NotNull
        private Duration deduplicationTtl = Duration.ofHours(24);
        @NotNull
        private Duration pendingMinIdle = Duration.ofMinutes(1);
        @NotBlank
        private String deadLetterKey = "{orders}:stream:dlq";
        @Min(1)
        @Max(10)
        private int maxProcessingAttempts = 5;
        @NotNull
        private Duration failureTrackingTtl = Duration.ofDays(7);
        @Min(1)
        private long deadLetterMaxLength = 100_000;
        @NotNull
        private Duration pelMonitorInterval = Duration.ofSeconds(15);
        private boolean consumerEnabled = true;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public String getConsumerName() {
            return consumerName;
        }

        public void setConsumerName(String consumerName) {
            this.consumerName = consumerName;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getPollTimeout() {
            return pollTimeout;
        }

        public void setPollTimeout(Duration pollTimeout) {
            this.pollTimeout = pollTimeout;
        }

        public Duration getDeduplicationTtl() {
            return deduplicationTtl;
        }

        public void setDeduplicationTtl(Duration deduplicationTtl) {
            this.deduplicationTtl = deduplicationTtl;
        }

        public boolean isConsumerEnabled() {
            return consumerEnabled;
        }

        public void setConsumerEnabled(boolean consumerEnabled) {
            this.consumerEnabled = consumerEnabled;
        }

        public Duration getPendingMinIdle() {
            return pendingMinIdle;
        }

        public void setPendingMinIdle(Duration pendingMinIdle) {
            this.pendingMinIdle = pendingMinIdle;
        }

        public String getDeadLetterKey() {
            return deadLetterKey;
        }

        public void setDeadLetterKey(String deadLetterKey) {
            this.deadLetterKey = deadLetterKey;
        }

        public int getMaxProcessingAttempts() {
            return maxProcessingAttempts;
        }

        public void setMaxProcessingAttempts(int maxProcessingAttempts) {
            this.maxProcessingAttempts = maxProcessingAttempts;
        }

        public Duration getFailureTrackingTtl() {
            return failureTrackingTtl;
        }

        public void setFailureTrackingTtl(Duration failureTrackingTtl) {
            this.failureTrackingTtl = failureTrackingTtl;
        }

        public long getDeadLetterMaxLength() {
            return deadLetterMaxLength;
        }

        public void setDeadLetterMaxLength(long deadLetterMaxLength) {
            this.deadLetterMaxLength = deadLetterMaxLength;
        }

        public Duration getPelMonitorInterval() {
            return pelMonitorInterval;
        }

        public void setPelMonitorInterval(Duration pelMonitorInterval) {
            this.pelMonitorInterval = pelMonitorInterval;
        }
    }

    public static class Provisioning {
        private boolean enabled;
        @NotNull
        private Authentication authentication = Authentication.INSTANCE_PRINCIPAL;
        private String configProfile = "DEFAULT";
        private String destinationRegion;
        private String displayName = "oci-cache-dr";
        private String compartmentOcid;
        private String subnetOcid;
        private List<String> nsgOcids = new ArrayList<>();
        @Min(3)
        private int nodeCount = 3;
        @NotNull
        private Mode mode = Mode.NON_SHARDED;
        @Min(3)
        private int shardCount = 3;
        @NotNull
        private EngineVersion engineVersion = EngineVersion.VALKEY_8_1;
        private float nodeMemoryGb = 2;
        private String backupOcid;
        private List<String> ociCacheUserOcids = new ArrayList<>();
        @NotNull
        private Duration readyTimeout = Duration.ofMinutes(30);
        @NotNull
        private Duration pollInterval = Duration.ofSeconds(20);
        @NotNull
        private Duration apiConnectTimeout = Duration.ofSeconds(5);
        @NotNull
        private Duration apiReadTimeout = Duration.ofSeconds(30);

        public enum Authentication {
            INSTANCE_PRINCIPAL,
            CONFIG_FILE
        }

        public enum EngineVersion {
            VALKEY_8_1,
            VALKEY_7_2,
            REDIS_7_0
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Authentication getAuthentication() {
            return authentication;
        }

        public void setAuthentication(Authentication authentication) {
            this.authentication = authentication;
        }

        public String getConfigProfile() {
            return configProfile;
        }

        public void setConfigProfile(String configProfile) {
            this.configProfile = configProfile;
        }

        public String getDestinationRegion() {
            return destinationRegion;
        }

        public void setDestinationRegion(String destinationRegion) {
            this.destinationRegion = destinationRegion;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getCompartmentOcid() {
            return compartmentOcid;
        }

        public void setCompartmentOcid(String compartmentOcid) {
            this.compartmentOcid = compartmentOcid;
        }

        public String getSubnetOcid() {
            return subnetOcid;
        }

        public void setSubnetOcid(String subnetOcid) {
            this.subnetOcid = subnetOcid;
        }

        public List<String> getNsgOcids() {
            return nsgOcids;
        }

        public void setNsgOcids(List<String> nsgOcids) {
            this.nsgOcids = nsgOcids;
        }

        public int getNodeCount() {
            return nodeCount;
        }

        public void setNodeCount(int nodeCount) {
            this.nodeCount = nodeCount;
        }

        public Mode getMode() {
            return mode;
        }

        public void setMode(Mode mode) {
            this.mode = mode;
        }

        public int getShardCount() {
            return shardCount;
        }

        public void setShardCount(int shardCount) {
            this.shardCount = shardCount;
        }

        public EngineVersion getEngineVersion() {
            return engineVersion;
        }

        public void setEngineVersion(EngineVersion engineVersion) {
            this.engineVersion = engineVersion;
        }

        public float getNodeMemoryGb() {
            return nodeMemoryGb;
        }

        public void setNodeMemoryGb(float nodeMemoryGb) {
            this.nodeMemoryGb = nodeMemoryGb;
        }

        public String getBackupOcid() {
            return backupOcid;
        }

        public void setBackupOcid(String backupOcid) {
            this.backupOcid = backupOcid;
        }

        public List<String> getOciCacheUserOcids() {
            return ociCacheUserOcids;
        }

        public void setOciCacheUserOcids(List<String> ociCacheUserOcids) {
            this.ociCacheUserOcids = ociCacheUserOcids;
        }

        public Duration getReadyTimeout() {
            return readyTimeout;
        }

        public void setReadyTimeout(Duration readyTimeout) {
            this.readyTimeout = readyTimeout;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getApiConnectTimeout() {
            return apiConnectTimeout;
        }

        public void setApiConnectTimeout(Duration apiConnectTimeout) {
            this.apiConnectTimeout = apiConnectTimeout;
        }

        public Duration getApiReadTimeout() {
            return apiReadTimeout;
        }

        public void setApiReadTimeout(Duration apiReadTimeout) {
            this.apiReadTimeout = apiReadTimeout;
        }
    }
}
