package com.example.eventstorage.client;

import com.example.eventstorage.client.config.EventStorageClientConfig;
import com.example.eventstorage.client.exception.EventStorageException;
import com.example.eventstorage.client.model.Event;
import com.example.eventstorage.client.model.EventResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Client for interacting with Event Storage Service
 * 
 * Example usage:
 * <pre>
 * EventStorageClient client = new EventStorageClient("http://localhost:8080");
 * 
 * Event event = Event.builder()
 *     .eventType("user_login")
 *     .userId("user-123")
 *     .build();
 * 
 * client.sendEvent(event);
 * </pre>
 */
@Slf4j
public class EventStorageClient {
    
    private final EventStorageClientConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * Create client with default configuration
     * 
     * @param baseUrl Base URL of Event Storage Service
     */
    public EventStorageClient(String baseUrl) {
        this(new EventStorageClientConfig(baseUrl));
    }
    
    /**
     * Create client with custom configuration
     * 
     * @param config Client configuration
     */
    public EventStorageClient(EventStorageClientConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    /**
     * Send event to default topic
     * 
     * @param event Event to send
     * @return Response from server
     * @throws EventStorageException if sending fails
     */
    public EventResponse sendEvent(Event event) {
        return sendEvent(event, config.getDefaultTopic());
    }
    
    /**
     * Send event to specific topic
     * 
     * @param event Event to send
     * @param topic Topic name
     * @return Response from server
     * @throws EventStorageException if sending fails
     */
    public EventResponse sendEvent(Event event, String topic) {
        validateEvent(event);
        enrichEvent(event);
        
        try {
            String url = config.getBaseUrl() + "/api/events/send?topic=" + topic;
            String eventJson = objectMapper.writeValueAsString(event);
            
            if (config.isLoggingEnabled()) {
                log.debug("Sending event to {}: {}", url, eventJson);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(eventJson, headers);
            
            ResponseEntity<EventResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                EventResponse.class
            );
            
            EventResponse eventResponse = response.getBody();
            
            if (config.isLoggingEnabled()) {
                log.debug("Event sent successfully: {}", eventResponse);
            }
            
            return eventResponse;
            
        } catch (RestClientException e) {
            String errorMsg = "Failed to send event to Event Storage Service: " + e.getMessage();
            log.error(errorMsg, e);
            throw new EventStorageException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Unexpected error while sending event: " + e.getMessage();
            log.error(errorMsg, e);
            throw new EventStorageException(errorMsg, e);
        }
    }
    
    /**
     * Send event with partition key to default topic
     * 
     * @param event Event to send
     * @param key Partition key
     * @return Response from server
     * @throws EventStorageException if sending fails
     */
    public EventResponse sendEventWithKey(Event event, String key) {
        return sendEventWithKey(event, key, config.getDefaultTopic());
    }
    
    /**
     * Send event with partition key to specific topic
     * 
     * @param event Event to send
     * @param key Partition key
     * @param topic Topic name
     * @return Response from server
     * @throws EventStorageException if sending fails
     */
    public EventResponse sendEventWithKey(Event event, String key, String topic) {
        validateEvent(event);
        enrichEvent(event);
        
        try {
            String url = config.getBaseUrl() + "/api/events/send-with-key?topic=" + topic + "&key=" + key;
            String eventJson = objectMapper.writeValueAsString(event);
            
            if (config.isLoggingEnabled()) {
                log.debug("Sending event with key to {}: {}", url, eventJson);
            }
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> request = new HttpEntity<>(eventJson, headers);
            
            ResponseEntity<EventResponse> response = restTemplate.exchange(
                url,
                HttpMethod.POST,
                request,
                EventResponse.class
            );
            
            EventResponse eventResponse = response.getBody();
            
            if (config.isLoggingEnabled()) {
                log.debug("Event with key sent successfully: {}", eventResponse);
            }
            
            return eventResponse;
            
        } catch (RestClientException e) {
            String errorMsg = "Failed to send event with key to Event Storage Service: " + e.getMessage();
            log.error(errorMsg, e);
            throw new EventStorageException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Unexpected error while sending event with key: " + e.getMessage();
            log.error(errorMsg, e);
            throw new EventStorageException(errorMsg, e);
        }
    }
    
    /**
     * Send event asynchronously (fire and forget)
     * 
     * @param event Event to send
     */
    public void sendEventAsync(Event event) {
        new Thread(() -> {
            try {
                sendEvent(event);
            } catch (Exception e) {
                log.error("Failed to send event asynchronously", e);
            }
        }).start();
    }
    
    /**
     * Validate event before sending
     */
    private void validateEvent(Event event) {
        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }
        if (event.getEventType() == null || event.getEventType().trim().isEmpty()) {
            throw new IllegalArgumentException("Event type is required");
        }
        if (event.getUserId() == null || event.getUserId().trim().isEmpty()) {
            throw new IllegalArgumentException("User ID is required");
        }
    }
    
    /**
     * Enrich event with default values
     */
    private void enrichEvent(Event event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now());
        }
    }
    
    /**
     * Get client configuration
     */
    public EventStorageClientConfig getConfig() {
        return config;
    }
}

