package com.aditya.cacheflow;

import com.aditya.cacheflow.config.AppConfig;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.*;

public class CachingProxyCommandTest {

    // Parses the args exactly like main() does, then returns the AppConfig that run() filled in
    private AppConfig runWith(String... args) {
        AppConfig config = new AppConfig();
        new CommandLine(new CachingProxyCommand(config)).execute(args);
        return config;
    }

    @Test
    void origin_withoutTrailingSlash_isUnchanged() {
        AppConfig config = runWith("--origin", "https://api.example.com");
        assertEquals("https://api.example.com", config.getOriginUrl());
    }

    @Test
    void origin_withTrailingSlash_isStripped() {
        AppConfig config = runWith("--origin=https://api.example.com/");

        // otherwise origin + "/products" would become "https://api.example.com//products"
        assertEquals("https://api.example.com", config.getOriginUrl());
    }

    @Test
    void origin_withMultipleTrailingSlashes_areAllStripped() {
        AppConfig config = runWith("--origin", "https://api.example.com///");
        assertEquals("https://api.example.com", config.getOriginUrl());
    }

    @Test
    void origin_withPathAndTrailingSlash_keepsPath() {
        AppConfig config = runWith("--origin", "https://api.example.com/v1/");
        assertEquals("https://api.example.com/v1", config.getOriginUrl());
    }

    @Test
    void stripTrailingSlashes_null_staysNull() {
        // no --origin given ; main() reports the missing origin, so we must not throw here
        assertNull(CachingProxyCommand.stripTrailingSlashes(null));
    }
}
