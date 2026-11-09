package com.example.fx.common.json;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import com.example.fx.common.model.TradePayload;
import org.apache.camel.component.jackson3.JacksonDataFormat;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonJsonConfiguration {
    @Bean
    public JsonMapper objectMapper() {
        // Persisted Jackson 2 events may omit primitive simulation flags.
        return JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .build();
    }

    @Bean
    public JacksonDataFormat tradePayloadJson(JsonMapper objectMapper) {
        // Do not rely on independent Camel defaults or implicit mapper discovery.
        return new JacksonDataFormat(objectMapper, TradePayload.class);
    }
}
