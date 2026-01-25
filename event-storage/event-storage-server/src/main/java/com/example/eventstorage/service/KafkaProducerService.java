package com.example.eventstorage.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaProducerService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    
    public void sendMessage(String topic, String message) {
        log.info("Sending message to topic {}: {}", topic, message);
        
        CompletableFuture<SendResult<String, String>> future = 
            kafkaTemplate.send(topic, message);
        
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Message successfully sent to topic {} with offset=[{}]", 
                    topic, result.getRecordMetadata().offset());
            } else {
                log.error("Error sending message to topic {}: {}", 
                    topic, ex.getMessage());
            }
        });
    }
    
    public void sendMessage(String topic, String key, String message) {
        log.info("Sending message with key to topic {}: key={}, message={}", 
            topic, key, message);
        
        CompletableFuture<SendResult<String, String>> future = 
            kafkaTemplate.send(topic, key, message);
        
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                log.info("Message with key successfully sent to topic {} with offset=[{}]", 
                    topic, result.getRecordMetadata().offset());
            } else {
                log.error("Error sending message with key to topic {}: {}", 
                    topic, ex.getMessage());
            }
        });
    }
}

