package com.aditya.cacheflow.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.* ;

@Service
public class ProxyService {
    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    private final RestTemplate restTemplate;

    public ProxyService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<String> forward(
            String targetUrl,
            HttpMethod method,
            HttpHeaders headers,
            byte[] body) {

        // Bundle headers and body into one object for RestTemplate
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(body, headers);

        try {
            //try and send a request
            ResponseEntity<String> response = restTemplate.exchange(
                    targetUrl, method, requestEntity, String.class );

            log.info("Response from origin : status={}", response.getStatusCode());
            return response;

        }  catch (HttpClientErrorException e) {
            // 4xx from origin — pass through as-is
            log.warn("Client error from origin: {}", e.getStatusCode());
            return ResponseEntity
                    .status(e.getStatusCode())
                    .body(e.getResponseBodyAsString());
        } catch (HttpServerErrorException e) {
            // 5xx from origin — pass through as-is
            log.warn("Server error from origin: {}", e.getStatusCode());
            return ResponseEntity
                    .status(e.getStatusCode())
                    .body(e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            // Origin unreachable — return 502
            log.error("Could not reach origin: {}", e.getMessage());
            return ResponseEntity
                    .status(502)
                    .body("could not connect to origin server");
        }
    }
}
