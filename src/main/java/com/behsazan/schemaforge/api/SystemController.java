package com.behsazan.schemaforge.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", "4.0.0-SNAPSHOT");
    }
}
