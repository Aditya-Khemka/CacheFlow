package com.aditya.cacheflow.service;

import com.aditya.cacheflow.config.AppConfig;
import com.aditya.cacheflow.model.CachedResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Lazy
@Service
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    //more than a normal hashmap; allows for concurrent usage
    private final AppConfig appConfig;
    private final Cache<String, CachedResponse> cache;

    public CacheService(AppConfig appConfig) {
        this.appConfig = appConfig;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(appConfig.getTtlMinutes(), TimeUnit.MINUTES)
                .maximumSize(appConfig.getMaxEntries())
                .build();
    }

    // returns the origin URL, which will act as a key in our HashMap
    public String buildKey(String method, String uri, String queryString) {

        //  ex : "GET:/products?limit=5&skip=0"
        if (queryString != null && !queryString.isBlank()) {
            return method + ":" + uri + "?" + queryString;
        }
        return method + ":" + uri;
    }

    // Check if a cached response exist for this key
    public boolean has(String key) {
        return cache.getIfPresent(key) != null;
    }

    public CachedResponse get(String key) {
        log.info("Cache HIT  : {}", key);
        log.info("TTL: {}", appConfig.getTtlMinutes());
        log.info("Max Entries: {}", appConfig.getMaxEntries());
        return cache.getIfPresent(key);
    }

    public void put(String key, CachedResponse response) {
        cache.put(key, response);
        log.info("MISS : Cached response for : {}", key);
        log.info("TTL: {}", appConfig.getTtlMinutes());
        log.info("Max Entries: {}", appConfig.getMaxEntries());
    }

    public void clear() {
        long size = cache.estimatedSize();
        cache.invalidateAll();
        log.info("Cache cleared — {} entries removed", size);
    }
}
