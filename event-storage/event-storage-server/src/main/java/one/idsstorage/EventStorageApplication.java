package one.idsstorage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EventStorageApplication {

    public static void main(String[] args) {
        SpringApplication.run(EventStorageApplication.class, args);
    }
}
