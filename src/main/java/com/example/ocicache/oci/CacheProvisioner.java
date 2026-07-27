package com.example.ocicache.oci;

import com.example.ocicache.config.HaCacheProperties;
import java.util.Optional;

public interface CacheProvisioner {

    Optional<HaCacheProperties.Region> provisionOrFindStandby();
}
