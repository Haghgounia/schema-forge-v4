package com.behsazan.schemaforge.specification.validation.spelling;

import java.util.List;

public final class NoOpSpellCheckService implements SpellCheckService {
    @Override
    public List<SpellingError> check(String text) { return List.of(); }
}
