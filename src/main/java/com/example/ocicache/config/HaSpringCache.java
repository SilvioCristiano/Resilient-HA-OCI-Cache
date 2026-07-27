package com.example.ocicache.config;

import com.example.ocicache.core.HaCacheRouter;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;

final class HaSpringCache implements Cache {

    private final String name;
    private final String prefix;
    private final String versionKey;
    private final Duration ttl;
    private final HaCacheRouter router;
    private final GenericJackson2JsonRedisSerializer serializer;
    private final ConcurrentHashMap<String, Object> localLoadLocks = new ConcurrentHashMap<>();

    HaSpringCache(
            String name,
            String namespace,
            Duration ttl,
            HaCacheRouter router,
            GenericJackson2JsonRedisSerializer serializer) {
        this.name = name;
        this.prefix = namespace + ":" + name;
        this.versionKey = prefix + ":version";
        this.ttl = ttl;
        this.router = router;
        this.serializer = serializer;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return router;
    }

    @Override
    public ValueWrapper get(Object key) {
        Object value = read(key);
        return value == null ? null : new SimpleValueWrapper(value);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        Object value = read(key);
        if (value == null) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "Valor do cache " + name + " não é do tipo " + type.getName());
        }
        return type == null ? (T) value : type.cast(value);
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object cached = read(key);
        if (cached != null) {
            return (T) cached;
        }
        String lockKey = encodedKey(key);
        Object lock = localLoadLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            try {
                cached = read(key);
                if (cached != null) {
                    return (T) cached;
                }
                T loaded = valueLoader.call();
                if (loaded != null) {
                    put(key, loaded);
                }
                return loaded;
            } catch (Exception exception) {
                throw new ValueRetrievalException(key, valueLoader, exception);
            } finally {
                localLoadLocks.remove(lockKey, lock);
            }
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (value == null) {
            return;
        }
        router.set(physicalKey(key), encode(value), ttl);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        if (value == null) {
            return get(key);
        }
        String physicalKey = physicalKey(key);
        if (router.setIfAbsent(physicalKey, encode(value), ttl)) {
            return null;
        }
        Object existing = decode(router.get(physicalKey));
        return existing == null ? null : new SimpleValueWrapper(existing);
    }

    @Override
    public void evict(Object key) {
        router.delete(physicalKey(key));
    }

    @Override
    public void clear() {
        // Invalidação O(1), sem KEYS/SCAN. Entradas antigas expiram pelo TTL.
        router.increment(versionKey);
    }

    private Object read(Object key) {
        return decode(router.get(physicalKey(key)));
    }

    private String physicalKey(Object key) {
        String version = router.getPrimary(versionKey);
        return prefix + ":v" + (version == null ? "0" : version) + "::" + encodedKey(key);
    }

    private String encodedKey(Object key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(serializer.serialize(key));
    }

    private String encode(Object value) {
        return Base64.getEncoder().encodeToString(serializer.serialize(value));
    }

    private Object decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        return serializer.deserialize(Base64.getDecoder().decode(encoded));
    }
}
