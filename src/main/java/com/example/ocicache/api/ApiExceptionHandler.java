package com.example.ocicache.api;

import com.example.ocicache.core.CacheUnavailableException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(CacheUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, Object> cacheUnavailable(CacheUnavailableException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "status", 503,
                "error", "OCI Cache indisponível",
                "message", exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> invalidRequest(MethodArgumentNotValidException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "status", 400,
                "error", "Requisição inválida",
                "message", exception.getMessage());
    }
}
