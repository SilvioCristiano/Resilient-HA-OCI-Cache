package com.example.ocicache.oci;

import com.example.ocicache.config.HaCacheProperties;
import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.auth.InstancePrincipalsAuthenticationDetailsProvider;
import com.oracle.bmc.redis.RedisClusterClient;
import com.oracle.bmc.redis.model.CreateRedisClusterDetails;
import com.oracle.bmc.redis.model.AttachOciCacheUsersDetails;
import com.oracle.bmc.redis.model.RedisCluster;
import com.oracle.bmc.redis.model.RedisClusterSummary;
import com.oracle.bmc.redis.requests.CreateRedisClusterRequest;
import com.oracle.bmc.redis.requests.AttachOciCacheUsersRequest;
import com.oracle.bmc.redis.requests.GetRedisClusterRequest;
import com.oracle.bmc.redis.requests.ListAttachedOciCacheUsersRequest;
import com.oracle.bmc.redis.requests.ListRedisClustersRequest;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConditionalOnProperty(prefix = "app.cache.provisioning", name = "enabled", havingValue = "true")
public class OciCacheProvisioner implements CacheProvisioner {

    private static final Logger log = LoggerFactory.getLogger(OciCacheProvisioner.class);

    private final HaCacheProperties properties;

    public OciCacheProvisioner(HaCacheProperties properties) {
        this.properties = properties;
    }

    @Override
    public Optional<HaCacheProperties.Region> provisionOrFindStandby() {
        HaCacheProperties.Provisioning config = properties.getProvisioning();
        validate(config);

        ClientConfiguration clientConfiguration = ClientConfiguration.builder()
                .connectionTimeoutMillis(Math.toIntExact(config.getApiConnectTimeout().toMillis()))
                .readTimeoutMillis(Math.toIntExact(config.getApiReadTimeout().toMillis()))
                .build();
        try (RedisClusterClient client = RedisClusterClient.builder()
                .configuration(clientConfiguration)
                .build(authentication(config))) {
            client.setRegion(config.getDestinationRegion());
            RedisCluster cluster = findReusableCluster(client, config)
                    .orElseGet(() -> createCluster(client, config));
            RedisCluster ready = waitUntilReady(client, cluster.getId(), config);
            attachCacheUsers(client, ready.getId(), config);
            ready = waitUntilReady(client, ready.getId(), config);
            String fqdn = ready.getPrimaryFqdn();
            if (!StringUtils.hasText(fqdn)) {
                fqdn = ready.getDiscoveryFqdn();
            }
            if (!StringUtils.hasText(fqdn)) {
                throw new IllegalStateException("OCI Cache ficou ativo, mas não retornou um FQDN");
            }
            HaCacheProperties.Region region = new HaCacheProperties.Region();
            region.setName("provisioned-" + config.getDestinationRegion());
            region.setOciRegion(config.getDestinationRegion());
            region.setHosts(List.of(fqdn));
            region.setMode(ready.getClusterMode() == RedisCluster.ClusterMode.Sharded
                    ? HaCacheProperties.Mode.SHARDED
                    : HaCacheProperties.Mode.NON_SHARDED);
            region.setTls(true);
            region.setClusterOcid(ready.getId());
            properties.getRegions().stream()
                    .filter(HaCacheProperties.Region::isConfigured)
                    .findFirst()
                    .ifPresent(source -> {
                        region.setUsername(source.getUsername());
                        region.setPassword(source.getPassword());
                        region.setReadFromReplicas(source.isReadFromReplicas());
                    });
            if (StringUtils.hasText(ready.getReplicasFqdn())) {
                region.setReadHost(ready.getReplicasFqdn());
            }
            return Optional.of(region);
        } catch (Exception exception) {
            throw new IllegalStateException("Falha ao preparar OCI Cache na região de DR", exception);
        }
    }

    private void attachCacheUsers(
            RedisClusterClient client,
            String clusterOcid,
            HaCacheProperties.Provisioning config) {
        if (config.getOciCacheUserOcids().isEmpty()) {
            return;
        }
        List<String> alreadyAttached = client.listAttachedOciCacheUsers(
                        ListAttachedOciCacheUsersRequest.builder()
                                .redisClusterId(clusterOcid)
                                .build())
                .getItems()
                .stream()
                .map(user -> user.getOciCacheUserId())
                .toList();
        List<String> missing = config.getOciCacheUserOcids().stream()
                .filter(user -> !alreadyAttached.contains(user))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        client.attachOciCacheUsers(AttachOciCacheUsersRequest.builder()
                .redisClusterId(clusterOcid)
                .opcRetryToken(UUID.nameUUIDFromBytes(
                        (clusterOcid + ":" + String.join(",", missing))
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString())
                .attachOciCacheUsersDetails(AttachOciCacheUsersDetails.builder()
                        .ociCacheUsers(missing)
                        .build())
                .build());
        waitUntilUsersAttached(client, clusterOcid, config);
    }

    private void waitUntilUsersAttached(
            RedisClusterClient client,
            String clusterOcid,
            HaCacheProperties.Provisioning config) {
        Instant deadline = Instant.now().plus(config.getReadyTimeout());
        while (Instant.now().isBefore(deadline)) {
            List<String> attached = client.listAttachedOciCacheUsers(
                            ListAttachedOciCacheUsersRequest.builder()
                                    .redisClusterId(clusterOcid)
                                    .build())
                    .getItems()
                    .stream()
                    .map(user -> user.getOciCacheUserId())
                    .toList();
            if (attached.containsAll(config.getOciCacheUserOcids())) {
                return;
            }
            sleep(config.getPollInterval());
        }
        throw new IllegalStateException("Timeout aguardando associação dos OCI Cache users");
    }

    private AbstractAuthenticationDetailsProvider authentication(
            HaCacheProperties.Provisioning config) throws Exception {
        if (config.getAuthentication()
                == HaCacheProperties.Provisioning.Authentication.CONFIG_FILE) {
            return new ConfigFileAuthenticationDetailsProvider(config.getConfigProfile());
        }
        return InstancePrincipalsAuthenticationDetailsProvider.builder().build();
    }

    private Optional<RedisCluster> findReusableCluster(
            RedisClusterClient client,
            HaCacheProperties.Provisioning config) {
        return client.listRedisClusters(ListRedisClustersRequest.builder()
                        .compartmentId(config.getCompartmentOcid())
                        .displayName(config.getDisplayName())
                        .build())
                .getRedisClusterCollection()
                .getItems()
                .stream()
                .filter(cluster -> cluster.getLifecycleState() != RedisCluster.LifecycleState.Deleted)
                .filter(cluster -> cluster.getLifecycleState() != RedisCluster.LifecycleState.Deleting)
                .max(Comparator.comparing(
                        RedisClusterSummary::getTimeCreated,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(summary -> client.getRedisCluster(GetRedisClusterRequest.builder()
                                .redisClusterId(summary.getId())
                                .build())
                        .getRedisCluster());
    }

    private RedisCluster createCluster(
            RedisClusterClient client,
            HaCacheProperties.Provisioning config) {
        log.warn("Nenhum standby reutilizável encontrado. Provisionando OCI Cache em {}",
                config.getDestinationRegion());
        CreateRedisClusterDetails.Builder details = CreateRedisClusterDetails.builder()
                .displayName(config.getDisplayName())
                .compartmentId(config.getCompartmentOcid())
                .clusterMode(config.getMode() == HaCacheProperties.Mode.SHARDED
                        ? RedisCluster.ClusterMode.Sharded
                        : RedisCluster.ClusterMode.Nonsharded)
                .nodeCount(config.getNodeCount())
                .nodeMemoryInGBs(config.getNodeMemoryGb())
                .softwareVersion(softwareVersion(config.getEngineVersion()))
                .subnetId(config.getSubnetOcid())
                .nsgIds(config.getNsgOcids())
                .freeformTags(java.util.Map.of(
                        "managed-by", "resilient-ha-oci-cache",
                        "dr-region", config.getDestinationRegion()));
        if (config.getMode() == HaCacheProperties.Mode.SHARDED) {
            details.shardCount(config.getShardCount());
        }
        if (StringUtils.hasText(config.getBackupOcid())) {
            applyBackup(details, config.getBackupOcid());
        }

        String retryToken = UUID.nameUUIDFromBytes(
                (config.getDisplayName() + ":" + config.getDestinationRegion())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        RedisCluster created = client.createRedisCluster(CreateRedisClusterRequest.builder()
                        .createRedisClusterDetails(details.build())
                        .opcRetryToken(retryToken)
                        .build())
                .getRedisCluster();
        if (created == null || !StringUtils.hasText(created.getId())) {
            return findClusterWithRetries(client, config);
        }
        return created;
    }

    private RedisCluster.SoftwareVersion softwareVersion(
            HaCacheProperties.Provisioning.EngineVersion version) {
        return switch (version) {
            case VALKEY_8_1 -> RedisCluster.SoftwareVersion.Valkey81;
            case VALKEY_7_2 -> RedisCluster.SoftwareVersion.Valkey72;
            case REDIS_7_0 -> RedisCluster.SoftwareVersion.Redis70;
        };
    }

    private void applyBackup(CreateRedisClusterDetails.Builder details, String backupOcid) {
        try {
            details.getClass()
                    .getMethod("backupId", String.class)
                    .invoke(details, backupOcid);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(
                    "A versão configurada do OCI Java SDK não suporta restore por backup; "
                            + "atualize o oci-java-sdk-redis",
                    exception);
        }
    }

    private RedisCluster findClusterWithRetries(
            RedisClusterClient client,
            HaCacheProperties.Provisioning config) {
        Instant deadline = Instant.now().plus(config.getPollInterval().multipliedBy(3));
        while (Instant.now().isBefore(deadline)) {
            Optional<RedisCluster> cluster = findReusableCluster(client, config);
            if (cluster.isPresent()) {
                return cluster.get();
            }
            sleep(config.getPollInterval());
        }
        throw new IllegalStateException("A criação foi aceita, mas o cluster ainda não está listável");
    }

    private RedisCluster waitUntilReady(
            RedisClusterClient client,
            String clusterOcid,
            HaCacheProperties.Provisioning config) {
        Instant deadline = Instant.now().plus(config.getReadyTimeout());
        while (Instant.now().isBefore(deadline)) {
            RedisCluster cluster = client.getRedisCluster(GetRedisClusterRequest.builder()
                            .redisClusterId(clusterOcid)
                            .build())
                    .getRedisCluster();
            if (cluster.getLifecycleState() == RedisCluster.LifecycleState.Active) {
                return cluster;
            }
            if (cluster.getLifecycleState() == RedisCluster.LifecycleState.Failed) {
                throw new IllegalStateException("OCI falhou ao criar o cluster: "
                        + cluster.getLifecycleDetails());
            }
            sleep(config.getPollInterval());
        }
        throw new IllegalStateException("Timeout aguardando o OCI Cache ficar ativo");
    }

    private void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provisionamento interrompido", exception);
        }
    }

    private void validate(HaCacheProperties.Provisioning config) {
        if (!StringUtils.hasText(config.getDestinationRegion())
                || !StringUtils.hasText(config.getCompartmentOcid())
                || !StringUtils.hasText(config.getSubnetOcid())) {
            throw new IllegalStateException(
                    "destination-region, compartment-ocid e subnet-ocid são obrigatórios para provisionamento");
        }
    }
}
