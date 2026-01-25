package com.example.eventstorage.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Event model for sending events to Event Storage Service
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Event {
    
    /**
     * Unique event identifier (optional, will be generated if not provided)
     */
    private String eventId;
    
    /**
     * Type of event (required)
     */
    private String eventType;
    
    /**
     * User identifier (required)
     */
    private String userId;
    
    /**
     * Session identifier (optional)
     */
    private String sessionId;
    
    /**
     * Order identifier (optional)
     */
    private String orderId;
    
    /**
     * Action description (optional)
     */
    private String action;
    
    /**
     * Amount value (optional)
     */
    private Double amount;
    
    /**
     * IP address (optional)
     */
    private String ipAddress;
    
    /**
     * Page URL (optional)
     */
    private String pageUrl;
    
    /**
     * Referrer URL (optional)
     */
    private String referrer;
    
    /**
     * Event timestamp (optional, current time if not provided)
     */
    private LocalDateTime timestamp;
    
    /**
     * Additional custom properties (optional)
     */
    private Map<String, Object> properties;
}

