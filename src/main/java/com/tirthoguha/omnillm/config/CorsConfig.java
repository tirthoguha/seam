package com.tirthoguha.omnillm.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Minimal CORS for the standalone demo UI (see {@code web/index.html}), which runs from a
 * different origin than the API (e.g. a {@code file://} page or a static dev server) and so needs
 * the browser to be told these endpoints may be read cross-origin.
 *
 * <p>Scope is deliberately narrow: only local origins, only the chat endpoints. This exists for
 * the local demo client — it is not a general "allow any site" policy.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/chat/**")
                // localhost on any port (dev servers) plus "null" for pages opened via file://
                .allowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*", "null")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }
}
