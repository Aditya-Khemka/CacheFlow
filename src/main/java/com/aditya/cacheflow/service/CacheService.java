package com.aditya.cacheflow.service;

import com.aditya.cacheflow.config.AppConfig;
import com.aditya.cacheflow.controller.CacheEntryDTO;
import com.aditya.cacheflow.model.CachedResponse;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

//    ===================disk logging===================
    private static final String CACHE_FILE = "cache.json";
    private final ObjectMapper objectMapper;

    //more than a normal hashmap; allows for concurrent usage
    private final AppConfig appConfig;
    private Cache<String, CachedResponse> cache;

    public CacheService(AppConfig appConfig) {
        this.appConfig = appConfig;

        /*
            about objectMapper
            ObjectMapper is Jackson's main class — translates between Java objects and JSON
            JavaTimeModule teaches Jackson how to read/write Java 8 date/time types
            Without this, LocalDateTime serialisation throws an error
         */
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

    }

    // Called once, the first time any cache operation is needed
    private Cache<String, CachedResponse> getCache() {
        if (cache == null) {
            log.info("Initialising Caffeine — TTL: {} mins, maxEntries: {}",
                    appConfig.getTtlMinutes(), appConfig.getMaxEntries());
            cache = Caffeine.newBuilder()
                    .expireAfterWrite(appConfig.getTtlMinutes(), TimeUnit.MINUTES)
                    .maximumSize(appConfig.getMaxEntries())
                    .build();
        }
        return cache;
    }

    // returns the origin URL, which will act as a key in our HashMap
    public String buildKey(String method, String uri, String queryString) {
        //  ex : GET:https://dummyjson.com/products?limit=5
        String base = method + ":" + appConfig.getOriginUrl() + uri;
        if (queryString != null && !queryString.isBlank()) {
            return base + "?" + queryString;
        }
        return base;
    }

    // Check if a cached response exist for this key
    public boolean has(String key) {
        return getCache().getIfPresent(key) != null;
    }

    public CachedResponse get(String key) {
        log.info("Cache HIT  : {}", key);
        return getCache().getIfPresent(key);
    }

    public void put(String key, CachedResponse response) {
        response.setCachedAt(LocalDateTime.now());
        getCache().put(key, response);
        log.info("MISS : Cached response for : {}", key);
    }

    public void clear() {
        long size = getCache().estimatedSize();
        getCache().invalidateAll();
        log.info("Cache cleared — {} entries removed", size);
    }


    @PreDestroy
    public void saveToFile() {

        //null check
        if (cache == null) {
            log.info("Cache not yet initialised — skipping save");
            return;
        }

        // Step 1 — get everything currently in Caffeine as a plain Java map
        // asMap() returns a ConcurrentMap of all non-expired entries
        Map<String, CachedResponse> liveEntries = getCache().asMap();

        if (liveEntries.isEmpty()) {
            log.info("Cache is empty — nothing to save");
            return;
        }

        // Step 2 — convert each CachedResponse into a CacheEntryDTO
        // We do this because HttpHeaders can't be serialised by Jackson directly
        List<CacheEntryDTO> dtos = new ArrayList<>();

        liveEntries.forEach((key, response) -> {

            // Convert HttpHeaders to Map<String, List<String>>
            // HttpHeaders.entrySet() gives us exactly this structure
                Map<String, List<String>> headersMap = new java.util.HashMap<>();
                if (response.getHeaders() != null) {
                    response.getHeaders().forEach((headerName, headerValues) ->
                            headersMap.put(headerName, headerValues)
                    );
                }

                dtos.add(new CacheEntryDTO(key, response.getStatusCode(), headersMap,
                        response.getBody(), response.getCachedAt()));
            }
        );

        // Step 3 — write the list to cache.json
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(CACHE_FILE), dtos);
            log.info("Saved {} entries to {}", dtos.size(), CACHE_FILE);

        } catch (IOException e) {
            log.error("Failed to write cache to disk: {}", e.getMessage());
        }
    }

    @Async
    @Scheduled(fixedRateString = "${cache.save-interval-ms:90000}")
    public void scheduledSave() {
        saveToFile();
    }
}
