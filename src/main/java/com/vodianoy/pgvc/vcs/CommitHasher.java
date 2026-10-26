package com.vodianoy.pgvc.vcs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 commit id over parent, metadata, SQL and schema snapshot (length-prefixed fields, like a git object id). */
final class CommitHasher {

    private CommitHasher() {
    }

    static String hash(String parentHash, String author, String message, String createdAt, String upSql,
                       String downSql, String snapshotJson) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{"pgvc-commit-v1", parentHash, author, message, createdAt, upSql, downSql,
                    snapshotJson}) {
                byte[] bytes = (part == null ? "" : part).getBytes(StandardCharsets.UTF_8);
                md.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
                md.update(bytes);
                md.update((byte) '\n');
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
