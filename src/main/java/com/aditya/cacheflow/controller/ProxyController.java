package com.aditya.cacheflow.controller;

import com.aditya.cacheflow.model.CachedResponse;
import com.aditya.cacheflow.service.CacheService;
import com.aditya.cacheflow.service.ProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aditya.cacheflow.config.AppConfig;
import org.springframework.context.annotation.Lazy;
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

    private final AppConfig appConfig; //get the origin server url and port number
    private final ProxyService proxyService; //actually send the request to the origin server
    private final CacheService cacheService; //store and retrieves cached responses

    public ProxyController(AppConfig appConfig, ProxyService proxyService, CacheService cacheService) {
        this.appConfig = appConfig;
        this.proxyService = proxyService;
        this.cacheService = cacheService;
    }

    @RequestMapping("/**")
    public ResponseEntity<String> handleRequest(HttpServletRequest request, HttpMethod method) throws IOException {

        // Step 1 : Extract Origin Details (ex : GET /users/1?page=2&limit=10)
        String uri          = request.getRequestURI(); // users/1
        String queryString  = request.getQueryString(); // page=2&limit=10 (anything after ?)
        HttpHeaders headers = extractHeaders(request); //incoming headers such as auth etc
        byte[] body         = request.getInputStream().readAllBytes(); //the entire request body (maybe JSON, XML etc)

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


    //request includes everything that was sent (headers, method, body etc)
    private HttpHeaders extractHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(name -> {
            if (!name.equalsIgnoreCase("host")
                    && !name.equalsIgnoreCase("content-length")
                    && !name.equalsIgnoreCase("transfer-encoding")
                    && !name.equalsIgnoreCase("accept-encoding")) {
                headers.set(name, request.getHeader(name));
            }
        });
        return headers;
        /*
        ex : Host: localhost:8080 , Authorization: Bearer abc123 , Accept: application/json , User-Agent: Chrome
        header names are Host , Authorization , Accept , User-Agent ;
        these are returned as Enumeration (legacy java iterator type) ; converted to list and then as HttpHeaders
         */
    }

    private ResponseEntity<String> handleCacheable(HttpMethod method, String uri, String queryString,
                                                   HttpHeaders requestHeaders, byte[] body, String targetUrl) {
        //buildKey (as in service class)
        String cacheKey = cacheService.buildKey(method.name(), uri, queryString);

        // HIT
        if (cacheService.has(cacheKey)) {
            CachedResponse cached = cacheService.get(cacheKey);
            return buildResponse(cached.getStatusCode(), cached.getHeaders(), cached.getBody(), "HIT");
        }
        // the controller returns ResponseEntity<String> and not CachedResponse (the key in our map)
        //hence, we need to reconstruct it everytime (also adds X-Cache = 'HIT')


        // MISS (forward and store)
        ResponseEntity<String> response = proxyService.forward(targetUrl, method, requestHeaders, body);

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

    //takes response from the origin server and duplicates it
    private ResponseEntity<String> buildResponse(int status, HttpHeaders originHeaders, String body, String cacheStatus) {
        HttpHeaders responseHeaders = new HttpHeaders();

        if (originHeaders != null) {
            responseHeaders.addAll(originHeaders);
            responseHeaders.remove("Content-Encoding");
            responseHeaders.remove("Transfer-Encoding");
        }

        responseHeaders.set("X-Cache", cacheStatus);
        //X-Cache tells if this result is from the origin or cache (can be used by the client to check)

        return ResponseEntity.status(status).headers(responseHeaders).body(body);
        //HTTP response always has 3 parts : status , headers and body. ResponseEntity is used to construct them
    }

}

