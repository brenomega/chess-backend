package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

@Configuration(proxyBeanMethods = false)
class AiJobJsonConfiguration {

    @Bean
    MappingJackson2HttpMessageConverter aiJobJsonConverter(ObjectMapper objectMapper) {
        return new MappingJackson2HttpMessageConverter(objectMapper) {
            @Override
            protected ObjectReader customizeReader(ObjectReader reader, JavaType type) {
                if (type.hasRawClass(AiJobNextRequest.class) || type.hasRawClass(AiJobResultRequest.class)) {
                    return reader.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
                }
                return reader;
            }
        };
    }
}
