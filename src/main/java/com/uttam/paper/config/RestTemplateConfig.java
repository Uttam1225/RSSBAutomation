package com.uttam.paper.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * Configures RestTemplate with connection and read timeouts.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);   // 10 seconds
        factory.setReadTimeout(240_000);      // 4 minutes — enough for fallback models on large prompts
        return new RestTemplate(factory);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }
}
