package com.behsazan.schemaforge.config;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumericMappingPropertiesTest {
    @Test
    void shouldBindStrategyFromSpringConfiguration() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of(
                "schemaforge.numeric-mapping.strategy", "OPTIMIZED")));

        NumericMappingProperties properties = binder
                .bind("schemaforge.numeric-mapping", Bindable.of(NumericMappingProperties.class))
                .get();

        assertEquals(NumericMappingStrategy.OPTIMIZED, properties.getStrategy());
        assertEquals("OPTIMIZED", properties.manifestValue().get("strategy"));
    }
}
