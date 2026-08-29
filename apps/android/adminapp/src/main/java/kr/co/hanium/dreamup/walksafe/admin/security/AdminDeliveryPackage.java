package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Verified delivery package held only in memory until the user chooses a SAF document. */
public final class AdminDeliveryPackage {
    private final String reportId;
    private final String packageId;
    private final int revision;
    private final String exportAuditId;
    private final String packageSha256;
    private final String csvSha256;
    private final String manifestSha256;
    private byte[] bytes;

    public AdminDeliveryPackage(
        String reportId,
        String packageId,
        int revision,
        String exportAuditId,
        String packageSha256,
        String csvSha256,
        String manifestSha256,
        byte[] bytes
    ) throws IOException {
        this.reportId = AdminReportModels.canonicalUuid(reportId, "report_id");
        this.packageId = AdminReportModels.canonicalUuid(packageId, "package_id");
        if (revision < 1) throw new IllegalArgumentException("package revision must be positive");
        this.revision = revision;
        this.exportAuditId = AdminReportModels.canonicalUuid(exportAuditId, "export_audit_id");
        this.packageSha256 = sha(packageSha256, "package_sha256");
        this.csvSha256 = sha(csvSha256, "csv_sha256");
        this.manifestSha256 = sha(manifestSha256, "manifest_sha256");
        if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024) {
            throw new IOException("delivery package size is invalid");
        }
        this.bytes = bytes.clone();
        if (!this.packageSha256.equals(digest(this.bytes))) {
            destroy();
            throw new IOException("delivery package digest is invalid");
        }
        try {
            verifyZipEntries();
        } catch (IOException error) {
            destroy();
            throw error;
        }
    }

    public String reportId() { return reportId; }
    public String packageId() { return packageId; }
    public int revision() { return revision; }
    public String exportAuditId() { return exportAuditId; }
    public String packageSha256() { return packageSha256; }
    public synchronized int byteCount() { return bytes == null ? 0 : bytes.length; }
    public synchronized byte[] copyBytes() throws IOException {
        if (bytes == null) throw new IOException("delivery package is no longer available");
        return bytes.clone();
    }
    public synchronized void destroy() {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
        bytes = null;
    }

    private void verifyZipEntries() throws IOException {
        byte[] value = copyBytes();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(value))) {
            Set<String> names = new HashSet<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !names.add(entry.getName())
                    || !(entry.getName().equals("report.csv") || entry.getName().equals("manifest.json"))) {
                    throw new IOException("delivery package entries are invalid");
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4_096];
                while (true) {
                    int read = zip.read(buffer);
                    if (read < 0) break;
                    if (output.size() + read > 2 * 1024 * 1024) {
                        throw new IOException("delivery package entry is too large");
                    }
                    output.write(buffer, 0, read);
                }
                String expected = entry.getName().equals("report.csv") ? csvSha256 : manifestSha256;
                if (!expected.equals(digest(output.toByteArray()))) {
                    throw new IOException("delivery package entry digest is invalid");
                }
            }
            if (!names.equals(AdminJava8Collections.set("report.csv", "manifest.json"))) {
                throw new IOException("delivery package entries are incomplete");
            }
        } finally {
            Arrays.fill(value, (byte) 0);
        }
    }

    static String digest(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] result = new char[digest.length * 2];
            char[] hex = "0123456789abcdef".toCharArray();
            for (int index = 0; index < digest.length; index++) {
                int current = digest[index] & 0xff;
                result[index * 2] = hex[current >>> 4];
                result[index * 2 + 1] = hex[current & 0x0f];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String sha(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }
}
