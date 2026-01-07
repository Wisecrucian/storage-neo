package com.example.eventstorage.controller;

import com.example.eventstorage.service.KafkaProducerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final KafkaProducerService kafkaProducerService;

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendEvent(
            @RequestParam(defaultValue = "events") String topic,
            @RequestBody String message) {
        
        kafkaProducerService.sendMessage(topic, message);
        
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Event sent to topic: " + topic
        ));
    }

    @PostMapping("/send-with-key")
    public ResponseEntity<Map<String, String>> sendEventWithKey(
            @RequestParam(defaultValue = "events") String topic,
            @RequestParam String key,
            @RequestBody String message) {
        
        kafkaProducerService.sendMessage(topic, key, message);
        
        return ResponseEntity.ok(Map.of(
            "status", "success",
            "message", "Event with key sent to topic: " + topic
        ));
    }
}

