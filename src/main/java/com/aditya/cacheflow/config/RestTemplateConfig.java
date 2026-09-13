package com.aditya.cacheflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Configuration @Component
public class RestTemplateConfig {
    /*
    ClientHttpRequestFactory handles the actual low-level TCP connection
    ie opening the socket, sending bytes, reading bytes back
     */

    //all methods inside a config need to be annotated with @bean
    @Bean
    public RestTemplate restTemplate() {
        // Create the factory that handles low-level HTTP connections
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        //time to setup a connection
        factory.setConnectTimeout(5000);

        //time to respond
        factory.setReadTimeout(5000);
        //If origin accepts connection but doesn't send a response within 5s, give up

        return new RestTemplate(factory);
    }

    // using new RestTemplate() instead would have created a new bean everytime ; timeout would be much difficult
}
