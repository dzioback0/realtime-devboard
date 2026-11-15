package com.example.realtimedevboard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RealtimeDevboardApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeDevboardApplication.class, args);
    }

}
