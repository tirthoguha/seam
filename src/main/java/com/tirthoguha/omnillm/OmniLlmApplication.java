package com.tirthoguha.omnillm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.tirthoguha.omnillm.config.LlmProperties;

@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class OmniLlmApplication {

    public static void main(String[] args) {
        SpringApplication.run(OmniLlmApplication.class, args);
    }
}
