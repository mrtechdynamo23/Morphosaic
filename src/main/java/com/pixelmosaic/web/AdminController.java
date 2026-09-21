package com.pixelmosaic.web;

import com.pixelmosaic.stats.UsageStats;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
public class AdminController {

    private final UsageStats stats;
    private final byte[] adminToken;

    public AdminController(UsageStats stats, @Value("${pixelmosaic.admin-token}") String adminToken) {
        this.stats = stats;
        this.adminToken = adminToken.getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/admin/stats")
    public ResponseEntity<Map<String, Object>> stats(
            @RequestHeader(value = "X-Admin-Token", required = false) String token) {
        if (adminToken.length == 0 || token == null
                || !MessageDigest.isEqual(adminToken, token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(Map.of(
                "processed", stats.processed(),
                "since", stats.since().toString()));
    }
}
