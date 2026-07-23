package com.behsazan.schemaforge.specification.normalization.check;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public final class CheckConstraintNormalizer {


    private static final Pattern VALUE_PATTERN =
            Pattern.compile("(?m)(\\d+)\\s*:");



    public String normalize(
            String columnName,
            String expression) {


        if (expression == null
                || expression.isBlank()) {

            return expression;
        }


        String valueExpression =
                extractValues(expression);


        if (valueExpression != null) {

            return columnName
                    + " IN ("
                    + valueExpression
                    + ")";
        }


        return expression.trim();
    }



    private String extractValues(
            String expression) {


        Matcher matcher =
                VALUE_PATTERN.matcher(expression);


        List<String> values =
                new ArrayList<>();


        while (matcher.find()) {

            values.add(
                    matcher.group(1)
            );
        }


        if (values.isEmpty()) {

            return null;
        }


        return String.join(
                ",",
                values
        );
    }
}