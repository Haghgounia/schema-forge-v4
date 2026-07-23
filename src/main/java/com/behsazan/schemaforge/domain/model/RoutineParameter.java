package com.behsazan.schemaforge.domain.model;
import com.behsazan.schemaforge.domain.enums.ParameterMode;
import com.behsazan.schemaforge.domain.valueobject.*;
import java.util.Objects;
public record RoutineParameter(Identifier name, DataType dataType, ParameterMode mode, DefaultValue defaultValue) {
    public RoutineParameter { Objects.requireNonNull(name); Objects.requireNonNull(dataType); mode = mode == null ? ParameterMode.IN : mode; defaultValue = defaultValue == null ? new DefaultValue(null) : defaultValue; }
}
