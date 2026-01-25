package one.idsstorage.client;

import one.idsstorage.client.config.EventStorageClientConfig;
import one.idsstorage.client.exception.EventStorageException;
import one.idsstorage.client.model.Event;
import one.idsstorage.client.model.EventResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class EventStorageClient {
    
    private static final Logger log = LoggerFactory.getLogger(EventStorageClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final EventStorageClientConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public EventStorageClient(String baseUrl) {
        this(new EventStorageClientConfig(baseUrl));
    }
    
    public EventStorageClient(EventStorageClientConfig config) {
        this.config = config;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectionTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(config.getReadTimeout(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }
    
    public EventResponse sendEvent(Event event) {
        return sendEvent(event, config.getDefaultTopic());
    }
    
    public EventResponse sendEvent(Event event, String topic) {
        validateEvent(event);
        enrichEvent(event);
        
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            String url = config.getBaseUrl() + "/api/events/send?topic=" + topic;
            
            if (config.isLoggingEnabled()) {
                log.debug("Sending event to {}: {}", url, eventJson);
            }
            
            RequestBody body = RequestBody.create(eventJson, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new EventStorageException("Failed to send event: HTTP " + response.code());
                }
                
                String responseBody = response.body().string();
                EventResponse eventResponse = objectMapper.readValue(responseBody, EventResponse.class);
                
                if (config.isLoggingEnabled()) {
                    log.debug("Event sent successfully: {}", eventResponse);
                }
                
                return eventResponse;
            }
            
        } catch (IOException e) {
            String errorMsg = "Failed to send event to Event Storage Service: " + e.getMessage();
            log.error(errorMsg, e);
            throw new EventStorageException(errorMsg, e);
        }
    }
    
    public EventResponse sendEventWithKey(Event event, String key) {
        return sendEventWithKey(event, key, config.getDefaultTopic());
    }
    
    public EventResponse sendEventWithKey(Event event, String key, String topic) {
        validateEvent(event);
        enrichEvent(event);
        
        try {
            String eventJson = objectMapper.writeValueAsString(event);
            String url = config.getBaseUrl() + "/api/events/send-with-key?topic=" + topic + "&key=" + key;
            
            if (config.isLoggingEnabled()) {
                log.debug("Sending event with key to {}: {}", url, eventJson);
            }
            
            RequestBody body = RequestBody.create(eventJson, JSON);
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new EventStorageException("Failed to send event with key: HTTP " + response.code());
                }
                
                String responseBody = response.body().string();
                EventResponse eventResponse = objectMapper.readValue(responseBody, EventResponse.class);
                
                if (config.isLoggingEnabled()) {
                    log.debug("Event with key sent successfully: {}", eventResponse);
                }
                
                return eventResponse;
            }
            
        } catch (IOException e) {
            String errorMsg = "Failed to send event with key to Event Storage Service: " + e.getMessage();
            log.error(errorMsg, e);
            throw new EventStorageException(errorMsg, e);
        }
    }
    
    public void sendEventAsync(Event event) {
        new Thread(() -> {
            try {
                sendEvent(event);
            } catch (Exception e) {
                log.error("Failed to send event asynchronously", e);
            }
        }).start();
    }
    
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
    
    private void enrichEvent(Event event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID().toString());
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now());
        }
    }
    
    public EventStorageClientConfig getConfig() {
        return config;
    }
    
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }
}

