package one.idsstorage.controller;

import one.idsstorage.service.EventPublisherService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventPublisherService eventPublisherService;

    public EventController(EventPublisherService eventPublisherService) {
        this.eventPublisherService = eventPublisherService;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestParam(defaultValue = "events") String topic
    ) {
        try {
            String eventId = eventPublisherService.publish(topic, request);
            return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Event sent to topic: " + topic,
                    "eventId", eventId
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "Failed to send event: " + e.getMessage()
            ));
        }
    }
}
