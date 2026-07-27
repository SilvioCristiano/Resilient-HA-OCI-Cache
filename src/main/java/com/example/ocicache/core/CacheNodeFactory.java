package com.example.ocicache.core;

import com.example.ocicache.config.HaCacheProperties;

public interface CacheNodeFactory {

    CacheNode create(HaCacheProperties.Region region);
}
