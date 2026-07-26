package com.behsazan.schemaforge.specification.validation.spelling;

import java.util.List;

@FunctionalInterface
public interface SpellCheckService {
    List<SpellingError> check(String text);
}
