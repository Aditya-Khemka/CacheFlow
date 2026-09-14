package com.aditya.cacheflow.controller;

import com.aditya.cacheflow.model.CachedResponse;
import com.aditya.cacheflow.service.CacheService;
import com.aditya.cacheflow.service.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aditya.cacheflow.config.AppConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.*;

@Component
@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final AppConfig appConfig;
    private final ProxyService proxyService;
    private final CacheService cacheService;

    public ProxyController(AppConfig appConfig, ProxyService proxyService, CacheService cacheService) {
        this.appConfig = appConfig;
        this.proxyService = proxyService;
        this.cacheService = cacheService;
    }

    @RequestMapping("/**")
    public ResponseEntity<String> handleRequest(HttpServletRequest request,
                                                HttpMethod method) throws IOException {
        // Step 1 : Extract Origin Details
        String uri          = request.getRequestURI();
        String queryString  = request.getQueryString();
        HttpHeaders headers = extractHeaders(request);
        byte[] body         = request.getInputStream().readAllBytes();
        String targetUrl    = appConfig.getOriginUrl() + uri + (queryString != null ? "?" + queryString : "");

        System.out.println("\n\n================================ new request ================================\n");
        log.info("Incoming request    : {} {}", method, uri);
        log.info("Target URL (origin) : {}", targetUrl);
        if (body.length > 0) {
            log.info("Body            : {}", new String(body));
        }



        // Step 2:  Cache logic (only store GET responses)
        if (method.equals(HttpMethod.GET)) {
            return handleCacheable(method, uri, queryString, headers, body, targetUrl);
        }

        // Non-cacheable — forward directly
        log.info("Non-cacheable method {}", method);
        return proxyService.forward(targetUrl, method, headers, body);
    }


    private HttpHeaders extractHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!name.equalsIgnoreCase("host")
                    && !name.equalsIgnoreCase("content-length")
                    && !name.equalsIgnoreCase("transfer-encoding")) {
                headers.set(name, request.getHeader(name));
            }
        });
        return headers;
    }

    private ResponseEntity<String> handleCacheable(HttpMethod method, String uri,
                                                   String queryString,
                                                   HttpHeaders requestHeaders,
                                                   byte[] body,
                                                   String targetUrl) {
        //buildKey (as in service class)
        String cacheKey = cacheService.buildKey(method.name(), uri, queryString);

        // HIT
        if (cacheService.has(cacheKey)) {
            CachedResponse cached = cacheService.get(cacheKey);
            log.info("HIT") ;
            return buildResponse(cached.getStatusCode(), cached.getHeaders(), cached.getBody(), "HIT");
        }

        // MISS (forward and store)
        ResponseEntity<String> response = proxyService.forward(targetUrl, method, requestHeaders, body);
        log.info("MISS") ;

        //only store successful responses
        if (response.getStatusCode().is2xxSuccessful()) {
            cacheService.put(cacheKey, new CachedResponse(
                    response.getStatusCode().value(),
                    response.getHeaders(),
                    response.getBody()
            ));
        }

        return buildResponse(response.getStatusCode().value(), response.getHeaders(), response.getBody(), "MISS");
    }

    private ResponseEntity<String> buildResponse(int status, HttpHeaders originHeaders, String body, String cacheStatus) {
        HttpHeaders responseHeaders = new HttpHeaders();
        if (originHeaders != null) {
            responseHeaders.addAll(originHeaders);
        }
        responseHeaders.set("X-Cache", cacheStatus);
        return ResponseEntity.status(status).headers(responseHeaders).body(body);
    }

}

