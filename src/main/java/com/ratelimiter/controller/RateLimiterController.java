package com.ratelimiter.controller;

import com.ratelimiter.model.Policy;
import com.ratelimiter.model.RateLimitResult;
import com.ratelimiter.service.RateLimiterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class RateLimiterController {

    private final RateLimiterService service;

    public RateLimiterController(RateLimiterService service) {
        this.service = service;
    }

    /**
     * Demo protected endpoint. Counts each call against the caller's bucket.
     * Override policy with ?policy=token_bucket or sliding_window.
     * Override client id with X-Client-Id header (otherwise remote IP).
     */
    @GetMapping("/limited")
    public ResponseEntity<?> limited(HttpServletRequest req,
                                     @RequestParam(defaultValue = "sliding_window") String policy) {
        String clientId = resolveClient(req);
        Policy p = Policy.valueOf(policy.toUpperCase());
        RateLimitResult r = service.check(clientId, p);

        var headers = new org.springframework.http.HttpHeaders();
        headers.add("X-RateLimit-Remaining", String.valueOf(r.remaining()));
        headers.add("X-RateLimit-Policy", p.name());
        if (!r.allowed()) {
            headers.add("Retry-After-Ms", String.valueOf(r.retryAfterMs()));
            return new ResponseEntity<>(Map.of(
                    "error", "rate_limited",
                    "retryAfterMs", r.retryAfterMs()
            ), headers, HttpStatus.TOO_MANY_REQUESTS);
        }
        return new ResponseEntity<>(Map.of(
                "ok", true,
                "clientId", clientId,
                "remaining", r.remaining(),
                "policy", p.name()
        ), headers, HttpStatus.OK);
    }

    /** Pure check endpoint - useful as a sidecar / gateway plugin. */
    @PostMapping("/check")
    public RateLimitResult check(@RequestBody CheckRequest body) {
        Policy p = Policy.valueOf(body.policy().toUpperCase());
        return service.check(body.clientId(), p);
    }

    private String resolveClient(HttpServletRequest req) {
        String h = req.getHeader("X-Client-Id");
        if (h != null && !h.isBlank()) return h;
        return req.getRemoteAddr();
    }

    public record CheckRequest(String clientId, String policy) {}
}
