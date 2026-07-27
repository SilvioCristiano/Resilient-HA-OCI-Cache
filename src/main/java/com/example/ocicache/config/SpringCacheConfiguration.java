package com.example.ocicache.config;

import com.example.ocicache.core.HaCacheRouter;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

@Configuration(proxyBeanMethods = false)
@EnableCaching
@ConditionalOnProperty(
        prefix = "app.cache.spring",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SpringCacheConfiguration {

    @Bean
    public GenericJackson2JsonRedisSerializer cacheValueSerializer() {
        return GenericJackson2JsonRedisSerializer.builder().build();
    }

    @Bean
    public CacheManager cacheManager(
            HaCacheRouter router,
            HaCacheProperties properties,
            GenericJackson2JsonRedisSerializer serializer) {
        HaCacheProperties.SpringCache config = properties.getSpring();
        return new HaSpringCacheManager(router, config, serializer);
    }
}
