package com.example.ocicache.core;

import com.example.ocicache.config.HaCacheProperties;
import org.springframework.stereotype.Component;

@Component
public class LettuceCacheNodeFactory implements CacheNodeFactory {

    private final HaCacheProperties properties;

    public LettuceCacheNodeFactory(HaCacheProperties properties) {
        this.properties = properties;
    }

    @Override
    public CacheNode create(HaCacheProperties.Region region) {
        return new LettuceCacheNode(region, properties.getFailover());
    }
}
