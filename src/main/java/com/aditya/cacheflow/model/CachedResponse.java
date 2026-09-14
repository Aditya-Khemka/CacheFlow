package com.aditya.cacheflow.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor

//stores GET responses ONLY
public class CachedResponse {
    private int statusCode;
    private HttpHeaders headers;
    private String body;
    private LocalDateTime cachedAt;

    public CachedResponse(int statusCode, HttpHeaders headers, String body) {
        this.statusCode = statusCode;
        this.headers = headers;
        this.body = body;
    }
    // 3-arg constructor: ProxyController builds a CachedResponse without knowing/caring about cachedAt.
    // cachedAt is left null here on purpose
    // CacheService.put() is the single place that stamps it,
    // so "when was this cached" always means "when put() was actually called"
}
