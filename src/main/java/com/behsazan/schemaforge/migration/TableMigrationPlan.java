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
        List<TableObjectChange> objectChanges,
        boolean tableDescriptionChanged) {

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
        this(platform, liveTable, desiredTable, columnChanges, List.of(), false);
    }


    public TableMigrationPlan(
            DatabasePlatform platform,
            Table liveTable,
            Table desiredTable,
            List<ColumnChange> columnChanges,
            List<TableObjectChange> objectChanges) {
        this(platform, liveTable, desiredTable, columnChanges, objectChanges, false);
    }

    public boolean empty() { return columnChanges.isEmpty() && objectChanges.isEmpty() && !tableDescriptionChanged; }

    public MigrationRisk highestRisk() {
        MigrationRisk risk = MigrationRisk.SAFE;
        for (ColumnChange change : columnChanges) {
            risk = MigrationRisk.max(risk, change.risk());
        }
        for (TableObjectChange change : objectChanges) {
            risk = MigrationRisk.max(risk, change.risk());
        }
        if (tableDescriptionChanged) risk = MigrationRisk.max(risk, MigrationRisk.SAFE);
        return risk;
    }

    public long count(MigrationRisk risk) {
        long columns = columnChanges.stream().filter(change -> change.risk() == risk).count();
        long objects = objectChanges.stream().filter(change -> change.risk() == risk).count();
        long metadata = tableDescriptionChanged && risk == MigrationRisk.SAFE ? 1 : 0;
        return columns + objects + metadata;
    }
}
