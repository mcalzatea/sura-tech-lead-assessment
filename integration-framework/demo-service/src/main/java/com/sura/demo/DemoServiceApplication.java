package com.sura.demo;

import com.sura.integration.annotation.EnableIntegrationClients;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableIntegrationClients(basePackages = "com.sura.demo.client")
public class DemoServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoServiceApplication.class, args);
    }
}
