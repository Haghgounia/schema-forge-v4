package com.behsazan.schemaforge.metadata.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class MetadataRepositorySupport {
    private MetadataRepositorySupport() { }

    static Set<String> normalizeNames(Set<String> names, boolean upperCase) {
        if (names == null) return Set.of();
        return names.stream().filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .map(name -> upperCase ? name.toUpperCase(Locale.ROOT) : name)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    static Map<String, MetadataColumnProfile> toProfiles(
            Map<String, List<MetadataTypeFrequency>> grouped) {
        Map<String, MetadataColumnProfile> result = new LinkedHashMap<>();
        grouped.forEach((name, frequencies) ->
                result.put(name, new MetadataColumnProfile(name, 0, frequencies)));
        return Map.copyOf(result);
    }
}
