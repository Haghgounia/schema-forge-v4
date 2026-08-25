package com.behsazan.schemaforge.config;

import com.behsazan.schemaforge.dialect.NumericMappingStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;
import java.util.Objects;

/** Global exact-numeric mapping policy used by REST/Spring generation paths. */
@ConfigurationProperties(prefix = "schemaforge.numeric-mapping")
public class NumericMappingProperties {
    private NumericMappingStrategy strategy = NumericMappingStrategy.SAFE;

    public NumericMappingStrategy getStrategy() {
        return strategy;
    }

    public void setStrategy(NumericMappingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
    }

    public Map<String, Object> manifestValue() {
        return Map.of("strategy", strategy.name());
    }

    public static NumericMappingProperties defaults() {
        return new NumericMappingProperties();
    }
}
