package com.aditya.cacheflow.service;

import com.aditya.cacheflow.config.AppConfig;
import com.aditya.cacheflow.model.CachedResponse;
import com.aditya.cacheflow.model.CacheEntryDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;


public class CacheServiceTest {
    private CacheService cacheService;
    private AppConfig mockConfig;
    private CachedResponse dummyResponse;

    @BeforeEach
    void setUp() {
        // Create a fake AppConfig using Mockito
        // mock() creates a fake object that returns null/0/false by default
        // we override the methods we care about using when().thenReturn()
        mockConfig = mock(AppConfig.class);
        when(mockConfig.getTtlMinutes()).thenReturn(15);
        when(mockConfig.getMaxEntries()).thenReturn(100);
        when(mockConfig.getOriginUrl()).thenReturn("https://dummyjson.com");

        // Create a real CacheService with the fake config
        // This is what we're actually testing
        cacheService = new CacheService(mockConfig);

        // Build Caffeine — same call as main() makes after Picocli runs
        cacheService.createCache();

        // A simple response object to reuse across tests
        dummyResponse = new CachedResponse(200, new HttpHeaders(), "{\"id\":1}");
    }

    @AfterEach
    void tearDown() {
        // Delete cache.json after every test so tests don't bleed into each other
        // If test A writes a file and test B reads it, test B's result depends on A
        // Tests must be independent — tearDown ensures that
        File file = new File("cache.json");
        if (file.exists()) {
            file.delete();
        }
    }

    //BuildKey Tests

    @Test
    void buildKey_withQueryString_includesQueryString() {
        String key = cacheService.buildKey("GET", "/products", "limit=5");

        // The key must include method, origin, path, and query string
        assertEquals("GET:https://dummyjson.com/products?limit=5", key);
    }

    @Test
    void buildKey_withoutQueryString_noQuestionMark() {
        String key = cacheService.buildKey("GET", "/products", null);

        // null queryString → no "?" appended
        assertEquals("GET:https://dummyjson.com/products", key);
    }

    @Test
    void buildKey_withBlankQueryString_treatedAsNoQueryString() {
        String key = cacheService.buildKey("GET", "/products", "   ");

        // blank queryString → treated same as null
        assertEquals("GET:https://dummyjson.com/products", key);
    }

    @Test
    void buildKey_differentMethods_produceDifferentKeys() {
        String getKey  = cacheService.buildKey("GET",  "/products", null);
        String postKey = cacheService.buildKey("POST", "/products", null);

        // GET and POST to same path must produce different keys
        // otherwise a cached GET response could be returned for a POST
        assertNotEquals(getKey, postKey);
    }


    // has() / put() / get()

    @Test
    void has_beforePut_returnsFalse() {
        // Nothing stored yet — has() must return false
        assertFalse(cacheService.has("GET:https://dummyjson.com/products"));
    }

    @Test
    void has_afterPut_returnsTrue() {
        cacheService.put("GET:https://dummyjson.com/products", dummyResponse);

        // After storing, has() must return true for the same key
        assertTrue(cacheService.has("GET:https://dummyjson.com/products"));
    }

    @Test
    void has_differentKey_returnsFalse() {
        cacheService.put("GET:https://dummyjson.com/products", dummyResponse);

        // Storing under one key must not affect a different key
        assertFalse(cacheService.has("GET:https://dummyjson.com/users"));
    }

    @Test
    void get_afterPut_returnsSameBody() {
        cacheService.put("GET:https://dummyjson.com/products", dummyResponse);
        CachedResponse result = cacheService.get("GET:https://dummyjson.com/products");

        // The body we get back must be exactly what we stored
        assertNotNull(result);
        assertEquals("{\"id\":1}", result.getBody());
    }

    @Test
    void get_afterPut_returnsSameStatusCode() {
        cacheService.put("GET:https://dummyjson.com/products", dummyResponse);
        CachedResponse result = cacheService.get("GET:https://dummyjson.com/products");

        assertNotNull(result);
        assertEquals(200, result.getStatusCode());
    }

    @Test
    void get_withoutPut_returnsNull() {
        // Nothing stored — get() must return null, not throw an exception
        CachedResponse result = cacheService.get("GET:https://dummyjson.com/products");
        assertNull(result);
    }

    @Test
    void put_stampsTimestamp() {
        LocalDateTime before = LocalDateTime.now();
        cacheService.put("GET:https://dummyjson.com/products", dummyResponse);
        LocalDateTime after = LocalDateTime.now();

        // put() must stamp cachedAt between when we called it
        // we test that cachedAt is not null and within the expected window
        assertNotNull(dummyResponse.getCachedAt());
        assertFalse(dummyResponse.getCachedAt().isBefore(before));
        assertFalse(dummyResponse.getCachedAt().isAfter(after));
    }

    // clear()

    @Test
    void clear_removesAllEntries() {
        cacheService.put("GET:https://dummyjson.com/products", dummyResponse);
        cacheService.put("GET:https://dummyjson.com/users",
                new CachedResponse(200, new HttpHeaders(), "{\"id\":2}"));

        cacheService.clear();

        // Both entries must be gone after clear()
        assertFalse(cacheService.has("GET:https://dummyjson.com/products"));
        assertFalse(cacheService.has("GET:https://dummyjson.com/users"));
    }

    @Test
    void clear_onEmptyCache_doesNotCrash() {
        // Calling clear() on an empty cache must not throw any exception
        assertDoesNotThrow(() -> cacheService.clear());
    }

    // restoreFromFile()

    @Test
    void restore_validEntry_isLoadedIntoCache() throws IOException {
        // Write a cache file with one entry that's 5 minutes old (within 15 min TTL)
        writeCacheFile(List.of(
                new CacheEntryDTO(
                        "GET:https://dummyjson.com/products",
                        200,
                        java.util.Map.of("Content-Type", List.of("application/json")),
                        "{\"id\":1}",
                        LocalDateTime.now().minusMinutes(5)   // 5 mins old, TTL is 15
                )
        ));

        // Create a fresh CacheService — simulates a server restart
        CacheService freshService = new CacheService(mockConfig);
        freshService.loadFromFile();
        freshService.createCache();
        freshService.restoreFromFile();

        // Entry was within TTL — must be in the cache
        assertTrue(freshService.has("GET:https://dummyjson.com/products"));
    }

    @Test
    void restore_expiredEntry_isSkipped() throws IOException {
        // Write a cache file with one entry that's 20 minutes old (past 15 min TTL)
        writeCacheFile(List.of(
                new CacheEntryDTO(
                        "GET:https://dummyjson.com/products",
                        200,
                        java.util.Map.of(),
                        "{\"id\":1}",
                        LocalDateTime.now().minusMinutes(20)  // 20 mins old, TTL is 15
                )
        ));

        CacheService freshService = new CacheService(mockConfig);
        freshService.loadFromFile();
        freshService.createCache();
        freshService.restoreFromFile();

        // Entry was past TTL — must NOT be in the cache
        assertFalse(freshService.has("GET:https://dummyjson.com/products"));
    }

    @Test
    void restore_nullTimestamp_isSkipped() throws IOException {
        // An entry with no timestamp cannot be age-checked — must be skipped
        writeCacheFile(List.of(
                new CacheEntryDTO(
                        "GET:https://dummyjson.com/products",
                        200,
                        java.util.Map.of(),
                        "{\"id\":1}",
                        null    // no timestamp
                )
        ));

        CacheService freshService = new CacheService(mockConfig);
        freshService.loadFromFile();
        freshService.createCache();
        freshService.restoreFromFile();

        assertFalse(freshService.has("GET:https://dummyjson.com/products"));
    }

    @Test
    void restore_entryExactlyAtTtlBoundary_isSkipped() throws IOException {
        // An entry cached exactly 15 minutes ago is AT the boundary
        // minutesElapsed >= ttlMinutes → should be skipped
        writeCacheFile(List.of(
                new CacheEntryDTO(
                        "GET:https://dummyjson.com/products",
                        200,
                        java.util.Map.of(),
                        "{\"id\":1}",
                        LocalDateTime.now().minusMinutes(15)  // exactly at TTL
                )
        ));

        CacheService freshService = new CacheService(mockConfig);
        freshService.loadFromFile();
        freshService.createCache();
        freshService.restoreFromFile();

        // >= means exactly at boundary is treated as expired
        assertFalse(freshService.has("GET:https://dummyjson.com/products"));
    }

    // saveToFile() / loadFromFile()

    @Test
    void saveAndLoad_roundTrip_restoresBothEntries() throws IOException {
        // Store two entries in the current cache
        cacheService.put("GET:https://dummyjson.com/products",
                new CachedResponse(200, new HttpHeaders(), "{\"products\":true}"));
        cacheService.put("GET:https://dummyjson.com/users",
                new CachedResponse(200, new HttpHeaders(), "{\"users\":true}"));

        // Save to file
        cacheService.saveToFile();

        // Simulate restart — fresh CacheService reads the file
        CacheService freshService = new CacheService(mockConfig);
        freshService.loadFromFile();
        freshService.createCache();
        freshService.restoreFromFile();

        // Both entries must survive the save/load round trip
        assertTrue(freshService.has("GET:https://dummyjson.com/products"));
        assertTrue(freshService.has("GET:https://dummyjson.com/users"));
    }

    @Test
    void loadFromFile_missingFile_doesNotCrash() {
        // No cache.json exists — loadFromFile() must handle this gracefully
        assertDoesNotThrow(() -> cacheService.loadFromFile());
    }

    @Test
    void loadFromFile_emptyFile_doesNotCrash() throws IOException {
        // Create an empty file
        new File("cache.json").createNewFile();
        assertDoesNotThrow(() -> cacheService.loadFromFile());
    }

    @Test
    void loadFromFile_malformedJson_doesNotCrash() throws IOException {
        // Write garbage to the file — not valid JSON
        try (FileWriter writer = new FileWriter("cache.json")) {
            writer.write("this is not valid json {{{{");
        }
        // Must not throw — must log a warning and continue
        assertDoesNotThrow(() -> cacheService.loadFromFile());
    }


    // Helper
    private void writeCacheFile(List<CacheEntryDTO> entries) throws IOException {
        // ObjectMapper with JavaTimeModule so it can write LocalDateTime
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File("cache.json"), entries);
    }
}
