package one.idsstorage.controller;

import one.idsstorage.service.ClickHouseReadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private final ClickHouseReadService clickHouseReadService;

    public StorageController(ClickHouseReadService clickHouseReadService) {
        this.clickHouseReadService = clickHouseReadService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "clickhouseUp", clickHouseReadService.ping()
        ));
    }

    @GetMapping("/events")
    public ResponseEntity<List<Map<String, Object>>> latestEvents(@RequestParam(defaultValue = "20") int limit)
            throws IOException, InterruptedException {
        return ResponseEntity.ok(clickHouseReadService.readLatest(limit));
    }

    @GetMapping("/search")
    public ResponseEntity<List<Map<String, Object>>> searchByCustomField(
            @RequestParam String field,
            @RequestParam String value,
            @RequestParam(defaultValue = "50") int limit
    ) throws IOException, InterruptedException {
        return ResponseEntity.ok(clickHouseReadService.searchByCustomField(field, value, limit));
    }
}
