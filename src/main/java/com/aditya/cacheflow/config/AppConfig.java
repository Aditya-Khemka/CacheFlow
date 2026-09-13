package com.aditya.cacheflow.config;

import lombok.Data;
import org.springframework.stereotype.Component;

@Data
@Component
public class AppConfig {
    String originUrl ;
    int portNo = 8080;

    public String getUrl() {
        return originUrl;
    }

    public void setUrl(String url) {
        this.originUrl = url;
    }

    public int getPortNo() {
        return portNo;
    }

    public void setPortNo(int portNo) {
        this.portNo = portNo;
    }

    @Override
    public String toString() {
        return "AppConfig{" +
                "url='" + originUrl + '\'' +
                ", portNo=" + portNo +
                '}';
    }
}
