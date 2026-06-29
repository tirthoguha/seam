package com.tirthoguha.omnillm.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.tirthoguha.omnillm.provider.ChatProvider;
import com.tirthoguha.omnillm.provider.ChatProviderRegistry;
import com.tirthoguha.omnillm.provider.openai.OpenAiChatProvider;

/**
 * Builds one {@link OpenAIClient} (and {@link OpenAiChatProvider}) per configured backend and
 * registers them all in a {@link ChatProviderRegistry}. The official SDK lets us override the base
 * URL per client, which is the whole trick: OpenAI cloud and Docker Model Runner both expose an
 * OpenAI-compatible {@code /v1} API, so the same adapter serves every backend.
 */
@Configuration
public class OpenAiConfig {

    @Bean
    public ChatProviderRegistry chatProviderRegistry(LlmProperties props) {
        Map<String, ChatProvider> providers = new LinkedHashMap<>();
        props.backends().forEach((name, backend) -> {
            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .apiKey(backend.apiKey())
                    .baseUrl(backend.baseUrl())
                    .build();
            providers.put(name, new OpenAiChatProvider(name, client));
        });
        return new ChatProviderRegistry(providers, props.defaultBackend());
    }
}
