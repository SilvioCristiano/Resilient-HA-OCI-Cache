package com.example.ocicache.oci;

import com.example.ocicache.config.HaCacheProperties;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.cache.provisioning",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true)
public class NoOpCacheProvisioner implements CacheProvisioner {

    @Override
    public Optional<HaCacheProperties.Region> provisionOrFindStandby() {
        return Optional.empty();
    }
}
