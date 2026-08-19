package com.behsazan.schemaforge.diagram;

import com.behsazan.schemaforge.domain.enums.ReferentialAction;
import com.behsazan.schemaforge.domain.model.Column;
import com.behsazan.schemaforge.domain.model.ForeignKey;
import com.behsazan.schemaforge.domain.model.PrimaryKey;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;
import com.behsazan.schemaforge.domain.valueobject.DataType;
import com.behsazan.schemaforge.domain.valueobject.Identifier;
import com.behsazan.schemaforge.domain.valueobject.QualifiedName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConceptualErdCardinalityTest {

    @Test
    void requiredNonUniqueForeignKeyProducesExactlyOneParentAndZeroToManyChildren() {
        Table child = child(false, false);
        ForeignKey fk = child.foreignKeys().getFirst();

        ConceptualErdCardinality cardinality = ConceptualErdCardinality.resolve(child, fk);

        assertEquals(ConceptualErdCardinality.End.EXACTLY_ONE, cardinality.parentEnd());
        assertEquals(ConceptualErdCardinality.End.ZERO_OR_MANY, cardinality.childEnd());
    }

    @Test
    void nullableUniqueForeignKeyProducesOptionalOneToOneRelationship() {
        Table child = child(true, true);
        ForeignKey fk = child.foreignKeys().getFirst();

        ConceptualErdCardinality cardinality = ConceptualErdCardinality.resolve(child, fk);

        assertEquals(ConceptualErdCardinality.End.ZERO_OR_ONE, cardinality.parentEnd());
        assertEquals(ConceptualErdCardinality.End.ZERO_OR_ONE, cardinality.childEnd());
    }

    private static Table child(boolean nullable, boolean unique) {
        Column parentId = nullable
                ? Column.nullable("PARENT_ID", DataType.numeric("NUMBER", 19, 0))
                : Column.required("PARENT_ID", DataType.numeric("NUMBER", 19, 0));
        Table.Builder builder = Table.builder("APP", "CHILD")
                .addColumn(Column.required("CHILD_ID", DataType.numeric("NUMBER", 19, 0)))
                .addColumn(parentId)
                .primaryKey(new PrimaryKey(id("PK_CHILD"), List.of(id("CHILD_ID"))))
                .addForeignKey(new ForeignKey(
                        id("FK_CHILD_PARENT"),
                        List.of(id("PARENT_ID")),
                        QualifiedName.of("APP", "PARENT"),
                        List.of(id("PARENT_ID")),
                        ReferentialAction.NO_ACTION,
                        ReferentialAction.NO_ACTION));
        if (unique) {
            builder.addUniqueKey(new UniqueKey(id("UK_CHILD_PARENT"), List.of(id("PARENT_ID"))));
        }
        return builder.build();
    }

    private static Identifier id(String value) {
        return Identifier.of(value);
    }
}
