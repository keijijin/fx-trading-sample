package com.example.fx.integration;

import com.example.fx.common.json.CommonJsonConfiguration;
import com.example.fx.common.json.JsonSupport;
import com.example.fx.common.model.TradePayload;
import org.junit.jupiter.api.Test;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Guards persisted Jackson 2 payload compatibility when switching to Jackson 3. */
class JsonContractTest {
    private static final String LEGACY_JSON = """
            {"tradeId":"synthetic-trade","orderId":"synthetic-order",
             "accountId":"synthetic-account","currencyPair":"USD/JPY","side":"BUY",
             "orderAmount":1000.00,"executedAmount":1000.00,"executedPrice":150.2500,
             "correlationId":"synthetic-correlation"}
            """;
    private final tools.jackson.databind.json.JsonMapper mapper =
            new CommonJsonConfiguration().objectMapper();

    @Test
    void readsLegacyPayloadWithoutLosingDecimalScaleOrMissingFlagDefaults() {
        TradePayload trade = mapper.readValue(LEGACY_JSON, TradePayload.class);
        assertEquals(new BigDecimal("1000.00"), trade.orderAmount());
        assertEquals(new BigDecimal("150.2500"), trade.executedPrice());
        assertFalse(trade.simulateCoverFailure());
        assertFalse(trade.simulateComplianceFailure());
    }

    @Test
    void camelUsesTheExplicitCompatibleTradeDataFormat() throws Exception {
        try (var context = new DefaultCamelContext()) {
            context.getRegistry().bind("tradePayloadJson",
                    new CommonJsonConfiguration().tradePayloadJson(mapper));
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:legacy-json")
                            .unmarshal("tradePayloadJson");
                }
            });
            context.start();
            try (var producer = context.createProducerTemplate()) {
                var trade = producer.requestBody("direct:legacy-json", LEGACY_JSON, TradePayload.class);
                assertEquals(new BigDecimal("150.2500"), trade.executedPrice());
                assertFalse(trade.simulateCoverFailure());
            }
        }
    }

    @Test
    void outboxWriterAndConsumerPreserveTheSameTradePayload() {
        TradePayload original = new TradePayload("synthetic-trade", "synthetic-order",
                "synthetic-account", "USD/JPY", "BUY", new BigDecimal("1000.00"),
                new BigDecimal("1000.00"), new BigDecimal("150.2500"),
                "synthetic-correlation", false, true, false, false, false, false);
        String persisted = new JsonSupport(mapper).write(original);
        assertEquals(original, mapper.readValue(persisted, TradePayload.class));
    }
}
