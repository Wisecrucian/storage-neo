package com.example.eventstorage.client.config;

import lombok.Data;

/**
 * Configuration for Event Storage Client
 */
@Data
public class EventStorageClientConfig {
    
    /**
     * Base URL of Event Storage Service (e.g., http://localhost:8080)
     */
    private String baseUrl;
    
    /**
     * Default topic name for events
     */
    private String defaultTopic = "events";
    
    /**
     * Connection timeout in milliseconds
     */
    private int connectionTimeout = 5000;
    
    /**
     * Read timeout in milliseconds
     */
    private int readTimeout = 10000;
    
    /**
     * Number of retry attempts
     */
    private int retryAttempts = 3;
    
    /**
     * Enable/disable logging
     */
    private boolean loggingEnabled = true;
    
    public EventStorageClientConfig(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}

