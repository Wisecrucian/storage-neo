package one.idsstorage.client;

/**
 * Client SDK for Event Storage Service
 */
public class EventStorageClient {
    
    private final String baseUrl;
    
    public EventStorageClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }
    
    public String getBaseUrl() {
        return baseUrl;
    }
}
