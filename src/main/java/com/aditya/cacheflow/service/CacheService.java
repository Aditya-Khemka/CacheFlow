package com.aditya.cacheflow.service;

import com.aditya.cacheflow.config.AppConfig;
import com.aditya.cacheflow.controller.CacheEntryDTO;
import com.aditya.cacheflow.model.CachedResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    private final AppConfig appConfig;
    private Cache<String, CachedResponse> cache;

//    ===================disk storage===================
    private static final String CACHE_FILE = "cache.json";
    private final ObjectMapper objectMapper;
    private List<CacheEntryDTO> pendingRestore = new ArrayList<>();


    public CacheService(AppConfig appConfig) {
        this.appConfig = appConfig;

        /*
            about objectMapper
            ObjectMapper is Jackson's main class that translates between Java objects and JSON
            JavaTimeModule teaches Jackson how to read/write Java date/time types
            Without this, LocalDateTime serialisation throws an error
         */
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void createCache() {
        log.info("Initialising Caffeine with TTL: {} mins, maxEntries: {}",
                appConfig.getTtlMinutes(), appConfig.getMaxEntries());
        cache = Caffeine.newBuilder()
                .expireAfterWrite(appConfig.getTtlMinutes(), TimeUnit.MINUTES)
                .maximumSize(appConfig.getMaxEntries())
                .build();
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
        return cache.getIfPresent(key) != null;
    }

    public CachedResponse get(String key) {
        log.info("Cache HIT  : {}", key);
        return cache.getIfPresent(key);
    }

    public void put(String key, CachedResponse response) {
        response.setCachedAt(LocalDateTime.now());
        cache.put(key, response);
        log.info("MISS : Cached response for : {}", key);
    }

    public void clear() {
        long size = cache.estimatedSize();
        cache.invalidateAll();
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
        Map<String, CachedResponse> liveEntries = cache.asMap();

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


    @PostConstruct //starts before any bean is created
    public void loadFromFile() {
        File file = new File(CACHE_FILE);

        if (!file.exists()) {
            log.info("No cache file found, starting fresh");
            return;
        }

        if (file.length() == 0) {
            log.info("Cache file is empty, starting fresh");
            return;
        }

        try {
            // Deserialise JSON array into List<CacheEntryDTO>
            // TypeReference tells Jackson the exact generic type to deserialise into
            // can't write List<CacheEntryDTO>.class in Java due to type erasure
            // TypeReference is Jackson's workaround for this
            List<CacheEntryDTO> entries = objectMapper.readValue(file,
                    new TypeReference<List<CacheEntryDTO>>() {});

            if (entries.isEmpty()) {
                log.info("Cache file had no entries, starting fresh");
                return;
            }

            // Don't touch Caffeine yet ; hold the entries until cache is created
            pendingRestore = entries;
            log.info("Read {} entries from cache file, will restore after startup",
                    entries.size());

        } catch (IOException e) {
            log.warn("Could not read cache file, starting fresh: {}", e.getMessage());
            pendingRestore = new ArrayList<>();
        }
    }

    //create the cache and dump from file ; explicitly called from main to gurantee cache creation
    public void restoreFromFile() {
        if (pendingRestore.isEmpty()) {
            log.info("Nothing to restore");
            return;
        }

        log.info("Restoring {} entries from previous session", pendingRestore.size());
        int restored = 0; int skipped = 0;

        for (CacheEntryDTO entry : pendingRestore) {

            // Skip entries with no timestamp
            if (entry.getCachedAt() == null) {
                skipped++;
                continue;
            }

            // ChronoUnit.MINUTES.between() gives minutes elapsed since cachedAt
            long minutesElapsed = ChronoUnit.MINUTES.between(entry.getCachedAt(), LocalDateTime.now());

            if (minutesElapsed >= appConfig.getTtlMinutes()) {
                log.info("Skipping expired entry: {} ({}mins old)", entry.getKey(), minutesElapsed);
                skipped++;
                continue;
            }

            // Convert DTO back to CachedResponse
            HttpHeaders headers = new HttpHeaders();
            if (entry.getHeaders() != null) {
                entry.getHeaders().forEach(headers::addAll);
            }

            CachedResponse response = new CachedResponse(entry.getStatusCode(), headers, entry.getBody());
            response.setCachedAt(entry.getCachedAt());

            // Put directly into Caffeine under the original key
            cache.put(entry.getKey(), response);
            restored++;
        }

        log.info("Restore complete — {} restored, {} skipped", restored, skipped);
        pendingRestore.clear();
    }

}
