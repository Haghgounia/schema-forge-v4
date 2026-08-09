package com.behsazan.schemaforge.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes stable SHA-256 identities for Word source files used by the snapshot cache. */
public final class SnapshotFileHash {
    private SnapshotFileHash() {
    }

    /** Returns the lowercase hexadecimal SHA-256 of a file without loading the entire file in memory. */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
