package com.example.eventstorage.client.exception;

/**
 * Exception thrown when event storage operations fail
 */
public class EventStorageException extends RuntimeException {
    
    public EventStorageException(String message) {
        super(message);
    }
    
    public EventStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}

