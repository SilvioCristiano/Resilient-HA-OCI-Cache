package com.example.ocicache.core;

import java.util.Map;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("ociCache")
public class OciCacheHealthIndicator implements HealthIndicator {

    private final HaCacheRouter router;

    public OciCacheHealthIndicator(HaCacheRouter router) {
        this.router = router;
    }

    @Override
    public Health health() {
        Map<String, Object> status = router.status();
        return "UP".equals(status.get("status"))
                ? Health.up().withDetails(status).build()
                : Health.down().withDetails(status).build();
    }
}
