package com.aditya.cacheflow.model;

import lombok.*;
import java.time.LocalDateTime;
import java.util.*;

@Data
@AllArgsConstructor
@NoArgsConstructor

public class CacheEntryDTO {
    private String key;
    private int statusCode;
    private Map<String, List<String>> headers;
    private String body;
    private LocalDateTime cachedAt;

}

/*
DTO stands for Data Transfer Object. Java class whose only job is to carry data from one place to another
Here, it transffers data from the running Caffeine cache to a JSON file on disk and back.

CachedResponse (runtime)          CacheEntryDTO (file)
─────────────────────────         ──────────────────────
int statusCode              →     int statusCode
HttpHeaders headers         →     Map<String, List<String>> headers
String body                 →     String body
LocalDateTime cachedAt      →     LocalDateTime cachedAt
 */
