package com.aditya.cacheflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    //RestTemplate is now a spring managed bean instead of just an ordinary object
    //in other words, we're making modifications to the RestTemplate class (used in ProxyService)
    @Bean
    public RestTemplate restTemplate() {

        //ClientHttpRequestFactory handles the actual low-level TCP connection
        //ie opening the socket, sending bytes, reading bytes back
        //CarFactory creates cars ; HttpRequestFactory creates multiple HTTP requests
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        //time to setup a connection
        factory.setConnectTimeout(5000);

        //time to respond
        factory.setReadTimeout(5000);
        //If origin accepts connection but doesn't send a response within 5s, give up

        return new RestTemplate(factory);
    }
    //without this config, we would need to manage the timing in proxyService ; hence the modification
}
