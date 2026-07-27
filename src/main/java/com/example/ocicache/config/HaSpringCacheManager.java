package com.example.ocicache.config;

import com.example.ocicache.core.HaCacheRouter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

final class HaSpringCacheManager implements CacheManager {

    private final Map<String, Cache> caches;

    HaSpringCacheManager(
            HaCacheRouter router,
            HaCacheProperties.SpringCache properties,
            GenericJackson2JsonRedisSerializer serializer) {
        Map<String, Cache> configured = new LinkedHashMap<>();
        for (String cacheName : properties.getCacheNames()) {
            configured.put(cacheName, new HaSpringCache(
                    cacheName,
                    properties.getNamespace(),
                    properties.ttlFor(cacheName),
                    router,
                    serializer));
        }
        this.caches = Map.copyOf(configured);
    }

    @Override
    public Cache getCache(String name) {
        return caches.get(name);
    }

    @Override
    public Collection<String> getCacheNames() {
        return caches.keySet();
    }
}
