package com.behsazan.schemaforge.glossary;

import java.util.List;

public record GlossaryPage(List<GlossaryTerm> items, int page, int size, long total) { }
