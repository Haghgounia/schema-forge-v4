package com.behsazan.schemaforge.artifact;

/**
 * Stable identifiers for the SchemaForge artifact metadata contract.
 *
 * <p>The version describes the metadata contract only. It does not version SQL semantics,
 * parser behavior, filenames, directory layout, or HTTP transport. Those concerns evolve
 * independently.</p>
 */
public final class ArtifactContract {

    public static final String VERSION = "1";

    private ArtifactContract() {
    }
}
