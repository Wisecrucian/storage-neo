package com.example.eventstorage.client.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response from Event Storage Service
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventResponse {
    
    /**
     * Status of the operation (success/error)
     */
    private String status;
    
    /**
     * Response message
     */
    private String message;
}

