package com.example.ocicache.stream;

/**
 * Indica que repetir o evento não produzirá sucesso (por exemplo, payload inválido).
 * O consumer envia a mensagem diretamente para a Dead Letter Stream.
 */
public class PermanentMessageProcessingException extends RuntimeException {

    public PermanentMessageProcessingException(String message) {
        super(message);
    }

    public PermanentMessageProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
