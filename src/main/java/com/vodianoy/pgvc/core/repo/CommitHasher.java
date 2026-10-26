package com.vodianoy.pgvc.core.repo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/** SHA-256 commit hash over parent, metadata, SQL and snapshot - like a git object id. */
public final class CommitHasher {

    private CommitHasher() {
    }

    public static String hash(String parentHash, String author, String message, Instant createdAt, String upSql,
                              String downSql, String snapshotJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{"pgvc-commit-v1", nz(parentHash), author, message, createdAt.toString(),
                    upSql, downSql, snapshotJson}) {
                byte[] bytes = nz(part).getBytes(StandardCharsets.UTF_8);
                md.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                md.update((byte) ':');
                md.update(bytes);
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
