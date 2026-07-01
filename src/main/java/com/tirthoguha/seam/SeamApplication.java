package com.tirthoguha.seam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.tirthoguha.seam.config.LlmProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class SeamApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeamApplication.class, args);
    }
}
