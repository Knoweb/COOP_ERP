package lk.coopfed.knoweb.m9integration.internal.journal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import lk.coopfed.knoweb.m9integration.query.IntegrationQueries.JournalLineView;

/**
 * The journal file writers by format version (wave 2, CR-29-1 item 2): the export handler writes
 * with {@link #CURRENT_VERSION} and stores the bytes; the reconciliation regenerates with the
 * version the export recorded and compares. Version 1 ({@link JournalFileV1}, frozen) is what the
 * exports made before m9integration V0006 are served with, having no stored file.
 */
public final class JournalFiles {

    /** The version the export handler writes today. */
    public static final short CURRENT_VERSION = 2;

    private JournalFiles() {}

    /** The file of an export's lines in the given format version; an unknown version is a defect. */
    public static byte[] bytes(int formatVersion, List<JournalLineView> lines, boolean provisional) {
        String csv =
                switch (formatVersion) {
                    case 1 -> JournalFileV1.csv(lines);
                    case 2 -> JournalFileV2.csv(lines, provisional);
                    default ->
                        throw new IllegalStateException("No journal file writer for format version " + formatVersion);
                };
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    /** The SHA-256 of the file's bytes, in lower-case hex (64 characters, journal_export.content_hash). */
    public static String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    /** The same hash of a file held as text (UTF-8), for the tests and the version-1 path. */
    public static String sha256(String content) {
        return sha256(content.getBytes(StandardCharsets.UTF_8));
    }
}
