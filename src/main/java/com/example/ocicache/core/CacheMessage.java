package com.example.ocicache.core;

import java.time.Instant;

public record CacheMessage(String redisId, String eventId, String payload, Instant producedAt) {
}
