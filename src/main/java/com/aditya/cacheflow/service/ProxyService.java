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

// this class takes the request from ProxyController, sends it to the origin server and returns the origin's response.
@Service
public class ProxyService {
    private static final Logger log = LoggerFactory.getLogger(ProxyService.class);

    //RestTemplate is used as a messenger between spring and any REST API
    //it can send HTTP requests and receive HTTP responses
    //we've modified it in RestTemplateConfig
    private final RestTemplate restTemplate;

    public ProxyService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ResponseEntity<String> forward(String targetUrl, HttpMethod method, HttpHeaders headers, byte[] body) {

        // Bundle headers and body into one object (because RestTemplate.exchange() expects HttpEntity)
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(body, headers);

        try {
            //try and send a request using RestTemplate
            ResponseEntity<String> response = restTemplate.exchange(targetUrl, method, requestEntity, String.class);
            //input  : targetUrl (origin) , method  (Put, Get etc) and requestEntity
            //output : the response body in String format => wrap in ResponseEntity
            //recall that ResponseEntity contains status , headers and body

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
            // Origin unreachable (timeout and all other possible scenarios)\
            Throwable cause = e.getCause();

            if (cause instanceof java.net.SocketTimeoutException) {
                log.error("Timeout while communicating with origin: {}", e.getMessage());
                return ResponseEntity.status(504).body("origin server timed out");

            }

            // Other connection problems
            log.error("Could not reach origin: {}", e.getMessage());
            return ResponseEntity.status(502).body("could not connect to origin server");
        }
    }
}
