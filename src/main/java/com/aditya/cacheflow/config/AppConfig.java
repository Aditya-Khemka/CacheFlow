package com.aditya.cacheflow.config;

import com.aditya.cacheflow.controller.ProxyController;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Data
@Component
public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private String originUrl = "https://dummyjson.com";
    private int portNo = 8080;
    private int ttlMinutes ;
    private int maxEntries ;

    //getters and setters handled by lombok

}
