package com.example.fx.common.json;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class JsonSupport {
    private final ObjectMapper objectMapper;

    public JsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Failed to serialize payload", exception);
        }
    }
}
