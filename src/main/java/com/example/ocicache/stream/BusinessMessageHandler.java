package com.example.ocicache.stream;

import com.example.ocicache.core.CacheMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class BusinessMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(BusinessMessageHandler.class);

    /**
     * Substitua por uma operação de negócio idempotente. O eventId deve ser salvo
     * junto com a transação no banco definitivo antes do ACK do Redis Stream.
     */
    public void handle(CacheMessage message) {
        log.info("Evento processado: eventId={}, redisId={}, payload={}",
                message.eventId(), message.redisId(), message.payload());
    }
}
