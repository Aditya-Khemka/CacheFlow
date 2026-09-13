package com.aditya.cacheflow.controller;

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

    //log
    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    //appConfig to get port and url
    private AppConfig appConfig;
    private final ProxyService proxyService;

    public ProxyController(AppConfig appConfig ,  ProxyService proxyService) {
        this.appConfig = appConfig;
        this.proxyService = proxyService;
    }

    @RequestMapping("/**")
    public ResponseEntity<String> handleRequest(HttpServletRequest request,
                                                HttpMethod method) throws IOException {

        //step 1 : extract information from the incoming url
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();

        //System.out.println(request.getRequestURL().toString());

        //step 2 : filter headers
        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames()).forEach(headerName -> {
            if (!headerName.equalsIgnoreCase("host")
                    && !headerName.equalsIgnoreCase("content-length")
                    && !headerName.equalsIgnoreCase("transfer-encoding")) {
                headers.set(headerName, request.getHeader(headerName));
            }
        });
        //getHeaderNames() returns an Enumeration (legacy java iterator type)

        byte[] body = request.getInputStream().readAllBytes();
        //the body may be JSON, hence a byte array and not string

        //targetUrl = url to forward to
        String targetUrl = appConfig.getOriginUrl() + uri
                + (queryString != null ? "?" + queryString : "");

        //step 4: logs
        log.info("Incoming request      : {} {}", method, uri);
        log.info("Target URL (origin)   : {}", targetUrl);
        log.info("Headers               : {}", headers);
        if (body.length > 0) {
            log.info("Body              : {}", new String(body));
        }

        return proxyService.forward(targetUrl, method, headers, body);
    }

}
