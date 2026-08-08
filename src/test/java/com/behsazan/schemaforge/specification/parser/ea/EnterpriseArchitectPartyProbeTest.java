package com.behsazan.schemaforge.specification.parser.ea;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;


/**
 * Diagnostic probe for parsing the representative Enterprise Architect Party XMI export.
 *
 * <p>The test exercises the real resource and prints aggregate table, column, key, index and
 * check-constraint counts. It is primarily a human-readable regression aid for EA import and
 * duplicate-table resolution rather than a replacement for assertion-based parser tests.</p>
 */
class EnterpriseArchitectPartyProbeTest {
    @Test
    void probe() throws Exception {
        Path path = Path.of("src/test/resources/Party_14050514.xml");
        try (var in = Files.newInputStream(path)) {
            var schema = new EnterpriseArchitectXmlParser("DPS").parse(path.getFileName().toString(), in);
            System.out.println("schema=" + schema.name().value());
            System.out.println("tables=" + schema.tables().size());
            System.out.println("columns=" + schema.tables().stream().mapToInt(t -> t.columns().size()).sum());
            System.out.println("pk=" + schema.tables().stream().filter(t -> t.primaryKey().isPresent()).count());
            System.out.println("fk=" + schema.tables().stream().mapToInt(t -> t.foreignKeys().size()).sum());
            System.out.println("indexes=" + schema.tables().stream().mapToInt(t -> t.indexes().size()).sum());
            System.out.println("uk=" + schema.tables().stream().mapToInt(t -> t.uniqueKeys().size()).sum());
            System.out.println("checks=" + schema.tables().stream().mapToInt(t -> t.checkConstraints().size()).sum());
            System.out.println("metadata=" + schema.metadata());
            schema.tables().forEach(t -> System.out.printf("%s cols=%d pk=%s fk=%d idx=%d uk=%d ck=%d%n",
                    t.qualifiedName().name().value(), t.columns().size(), t.primaryKey().map(x -> x.name().value()).orElse("-"),
                    t.foreignKeys().size(), t.indexes().size(), t.uniqueKeys().size(), t.checkConstraints().size()));
        }
    }
}
