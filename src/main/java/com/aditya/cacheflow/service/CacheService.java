package com.aditya.cacheflow.service;

import com.aditya.cacheflow.model.CachedResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class CacheService {
    private static final Logger log = LoggerFactory.getLogger(CacheService.class);

    //more than a normal hashmap; allows for concurrent usage
    private ConcurrentHashMap<String, CachedResponse> cache = new ConcurrentHashMap<>();

    public String buildKey(String method, String uri, String queryString) {

        //  ex : "GET:/products?limit=5&skip=0"
        if (queryString != null && !queryString.isBlank()) {
            return method + ":" + uri + "?" + queryString;
        }
        return method + ":" + uri;
    }

    // Check if a cached response exist for this key
    public boolean has(String key) {
        return cache.containsKey(key);
    }

    public CachedResponse get(String key) {
        log.info("Cache HIT  : {}", key);
        return cache.get(key);
    }

    public void put(String key, CachedResponse response) {
        cache.put(key, response);
        log.info("Cached response for : {}", key);
    }

    public void clear() {
        int size = cache.size();
        cache.clear();
        log.info("Cache cleared — {} entries removed", size);
    }
}
