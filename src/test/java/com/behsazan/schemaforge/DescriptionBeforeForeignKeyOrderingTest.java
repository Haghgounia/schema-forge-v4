package com.behsazan.schemaforge;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.application.DialectFactory;
import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.DatabaseSchema;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.DefaultValue;
import com.behsazan.schemaforge.domain.valueobject.Description;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import com.behsazan.schemaforge.generation.DdlGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DescriptionBeforeForeignKeyOrderingTest {

    @Test
    void documentationIsRenderedBeforeForeignKeysForEveryPlatform() {
        for (DatabasePlatform platform : DatabasePlatform.values()) {
            Table table = Table.builder("APP", "NODE")
                    .description("Node table description")
                    .addColumn(column("ID", false, "Identifier"))
                    .addColumn(column("PARENT_ID", true, "Parent identifier"))
                    .primaryKey(new PrimaryKey(Identifier.of("PK_NODE"), List.of(Identifier.of("ID"))))
                    .addForeignKey(new ForeignKey(
                            Identifier.of("FK_NODE_PARENT"),
                            List.of(Identifier.of("PARENT_ID")),
                            QualifiedName.of("APP", "NODE"),
                            List.of(Identifier.of("ID")),
                            ReferentialAction.NO_ACTION,
                            ReferentialAction.NO_ACTION))
                    .build();
            DatabaseSchema schema = DatabaseSchema.builder("APP").addTable(table).build();

            String sql = new DdlGenerator(DialectFactory.create(platform)).generate(schema);
            int description = firstDescriptionPosition(sql, platform);
            int foreignKey = sql.indexOf("FOREIGN KEY");

            assertTrue(description >= 0, "description missing for " + platform);
            assertTrue(foreignKey >= 0, "foreign key missing for " + platform);
            assertTrue(description < foreignKey, "description must precede FK for " + platform);
        }
    }

    private static int firstDescriptionPosition(String sql, DatabasePlatform platform) {
        return switch (platform) {
            case MYSQL, MARIADB -> sql.indexOf("COMMENT 'Identifier'");
            case SQLSERVER -> sql.indexOf("MS_Description");
            case ORACLE, POSTGRESQL, DB2_ZOS, DB2_LUW -> sql.indexOf("COMMENT ON TABLE");
        };
    }

    private static Column column(String name, boolean nullable, String description) {
        return new Column(
                Identifier.of(name),
                DataType.numeric("NUMBER", 19, 0),
                nullable,
                new DefaultValue(null),
                new Description(description),
                false,
                name.equals("ID") ? 1 : 2);
    }
}
