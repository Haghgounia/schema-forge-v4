package com.behsazan.schemaforge.migration;

import com.behsazan.schemaforge.application.DatabasePlatform;
import com.behsazan.schemaforge.domain.model.Table;

import java.util.List;
import java.util.Objects;

/** DBMS-aware migration plan from a live table to the desired canonical table. */
public record TableMigrationPlan(
        DatabasePlatform platform,
        Table liveTable,
        Table desiredTable,
        List<ColumnChange> columnChanges,
        List<TableObjectChange> objectChanges) {

    public TableMigrationPlan {
        Objects.requireNonNull(platform, "platform must not be null");
        Objects.requireNonNull(liveTable, "liveTable must not be null");
        Objects.requireNonNull(desiredTable, "desiredTable must not be null");
        columnChanges = columnChanges == null ? List.of() : List.copyOf(columnChanges);
        objectChanges = objectChanges == null ? List.of() : List.copyOf(objectChanges);
    }

    public TableMigrationPlan(
            DatabasePlatform platform,
            Table liveTable,
            Table desiredTable,
            List<ColumnChange> columnChanges) {
        this(platform, liveTable, desiredTable, columnChanges, List.of());
    }

    public boolean empty() { return columnChanges.isEmpty() && objectChanges.isEmpty(); }

    public MigrationRisk highestRisk() {
        MigrationRisk risk = MigrationRisk.SAFE;
        for (ColumnChange change : columnChanges) {
            risk = MigrationRisk.max(risk, change.risk());
        }
        for (TableObjectChange change : objectChanges) {
            risk = MigrationRisk.max(risk, change.risk());
        }
        return risk;
    }

    public long count(MigrationRisk risk) {
        long columns = columnChanges.stream().filter(change -> change.risk() == risk).count();
        long objects = objectChanges.stream().filter(change -> change.risk() == risk).count();
        return columns + objects;
    }
}
