package com.projetoxadrez.backend.game.rest;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.StreamUtils;

@Configuration(proxyBeanMethods = false)
class AiJobJsonConfiguration {

    @Bean
    MappingJackson2HttpMessageConverter aiJobJsonConverter(ObjectMapper objectMapper) {
        return new MappingJackson2HttpMessageConverter(objectMapper) {
            @Override
            public Object read(Type type, Class<?> contextClass, HttpInputMessage input) throws IOException {
                if (type != AiJobNextRequest.class && type != AiJobResultRequest.class) {
                    return super.read(type, contextClass, input);
                }
                try (PushbackReader body = new PushbackReader(new InputStreamReader(
                        StreamUtils.nonClosing(input.getBody()), StandardCharsets.UTF_8.newDecoder()))) {
                    int first = body.read();
                    if (first != -1 && first != '\uFEFF') {
                        body.unread(first);
                    }
                    return getObjectMapper().readerFor(getJavaType(type, contextClass))
                            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readValue(body);
                } catch (IOException exception) {
                    throw new HttpMessageNotReadableException("Invalid AI job JSON", exception, input);
                }
            }

            @Override
            protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
                return read(type, null, input);
            }
        };
    }
}
