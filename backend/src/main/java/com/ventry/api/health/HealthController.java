package com.ventry.api.health;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** compose·CI 스모크용. 서비스 API 6종은 docs/API_CONTRACT.md 기준으로 BE-01(D3)에서 추가. */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "ventry");
    }
}
