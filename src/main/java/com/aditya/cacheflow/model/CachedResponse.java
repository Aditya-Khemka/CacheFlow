package com.aditya.cacheflow.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Data
@AllArgsConstructor
@NoArgsConstructor

//stores GET responses ONLY
public class CachedResponse {
    private int statusCode;
    private HttpHeaders headers;
    private String body;
}
