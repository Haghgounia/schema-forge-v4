package com.behsazan.schemaforge.deployment;

import com.behsazan.schemaforge.domain.model.CheckConstraint;
import com.behsazan.schemaforge.domain.model.Index;
import com.behsazan.schemaforge.domain.model.Sequence;
import com.behsazan.schemaforge.domain.model.Table;
import com.behsazan.schemaforge.domain.model.UniqueKey;

import java.util.List;
import java.util.Objects;

/**
 * DBMS-neutral integrated deployment plan.
 *
 * <p>Sequences are pre-table objects. Primary keys remain part of CREATE TABLE because that is the
 * stable SchemaForge v4 behavior. Phase 2 contains table-local post-create objects. Cross-table
 * physical foreign keys are isolated in phase 3 so circular dependencies do not block table
 * creation. Phase 4 identifies tables that contain comments/descriptions or grant metadata.</p>
 */
public record IntegratedSchemaDeploymentPlan(
        ForeignKeyAnalysisResult foreignKeyAnalysis,
        List<Sequence> preTableSequences,
        List<Table> phase1Tables,
        List<TableOwnedObject<CheckConstraint>> phase2CheckConstraints,
        List<TableOwnedObject<UniqueKey>> phase2UniqueKeys,
        List<TableOwnedObject<Index>> phase2Indexes,
        List<ForeignKeyDeployment> phase3ForeignKeys,
        List<Table> phase4MetadataTables) {

    public IntegratedSchemaDeploymentPlan {
        Objects.requireNonNull(foreignKeyAnalysis, "foreignKeyAnalysis must not be null");
        preTableSequences = List.copyOf(Objects.requireNonNull(preTableSequences));
        phase1Tables = List.copyOf(Objects.requireNonNull(phase1Tables));
        phase2CheckConstraints = List.copyOf(Objects.requireNonNull(phase2CheckConstraints));
        phase2UniqueKeys = List.copyOf(Objects.requireNonNull(phase2UniqueKeys));
        phase2Indexes = List.copyOf(Objects.requireNonNull(phase2Indexes));
        phase3ForeignKeys = List.copyOf(Objects.requireNonNull(phase3ForeignKeys));
        phase4MetadataTables = List.copyOf(Objects.requireNonNull(phase4MetadataTables));
        if (!foreignKeyAnalysis.deployable()) {
            throw new IllegalArgumentException("Deployment plan cannot contain blocking FK analysis errors");
        }
    }

    /** Number of canonical table-local objects scheduled after CREATE TABLE and before foreign keys. */
    public int phase2ObjectCount() {
        return phase2CheckConstraints.size() + phase2UniqueKeys.size() + phase2Indexes.size();
    }
}
