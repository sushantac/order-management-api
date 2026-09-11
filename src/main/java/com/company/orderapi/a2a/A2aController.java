package com.company.orderapi.a2a;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class A2aController {

    private final String baseUrl;

    public A2aController(@Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    @GetMapping(value = "/.well-known/agent-card", produces = MediaType.APPLICATION_JSON_VALUE)
    public AgentCard agentCard() {
        return AgentCard.defaultCard(baseUrl);
    }

    @PostMapping(value = "/a2a/message", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> message(@RequestBody Map<String, Object> payload) {
        String text = String.valueOf(payload.getOrDefault("message", ""));
        return Map.of("reply", "A2A received: " + text, "agent", "order-management-api-agent");
    }
}
