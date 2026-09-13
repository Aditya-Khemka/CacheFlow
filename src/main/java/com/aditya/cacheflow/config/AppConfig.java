package com.aditya.cacheflow.config;

import lombok.Data;
import org.springframework.stereotype.Component;

@Data
@Component
public class AppConfig {
    private String originUrl ;
    private int portNo = 8080;

    //getters and setters handled by lombok
}
