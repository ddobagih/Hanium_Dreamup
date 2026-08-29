package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Writes only to a user-selected document and verifies the complete persisted bytes. */
public final class AdminDeliveryPackageSaver {
    public interface Destination {
        OutputStream openOutput() throws IOException;
        InputStream openInput() throws IOException;
        void delete() throws IOException;
    }

    public static final class Saved {
        private final String reportId;
        private final String packageId;
        private final int revision;
        private final String packageSha256;
        private final int byteCount;

        private Saved(
            String reportId,
            String packageId,
            int revision,
            String packageSha256,
            int byteCount
        ) {
            this.reportId = reportId;
            this.packageId = packageId;
            this.revision = revision;
            this.packageSha256 = packageSha256;
            this.byteCount = byteCount;
        }

        public String reportId() { return reportId; }
        public String packageId() { return packageId; }
        public int revision() { return revision; }
        public String packageSha256() { return packageSha256; }
        public int byteCount() { return byteCount; }

        public boolean matchesDelivery(String expectedReportId, long expectedRevision) {
            return reportId.equals(expectedReportId) && revision == expectedRevision;
        }
    }

    private AdminDeliveryPackageSaver() {}

    public static Saved save(AdminDeliveryPackage packageValue, Destination destination) throws IOException {
        if (packageValue == null || destination == null) {
            throw new IllegalArgumentException("package and SAF destination are required");
        }
        byte[] expected = packageValue.copyBytes();
        boolean verified = false;
        try {
            try (OutputStream output = destination.openOutput()) {
                if (output == null) throw new IOException("SAF output stream is unavailable");
                int offset = 0;
                while (offset < expected.length) {
                    int count = Math.min(16 * 1024, expected.length - offset);
                    output.write(expected, offset, count);
                    offset += count;
                }
                output.flush();
            }
            byte[] persisted;
            try (InputStream input = destination.openInput(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IOException("SAF verification stream is unavailable");
                byte[] buffer = new byte[16 * 1024];
                while (true) {
                    int read = input.read(buffer);
                    if (read < 0) break;
                    if (output.size() + read > expected.length) {
                        throw new IOException("SAF document length is invalid");
                    }
                    output.write(buffer, 0, read);
                }
                persisted = output.toByteArray();
            }
            if (persisted.length != expected.length
                || !packageValue.packageSha256().equals(AdminDeliveryPackage.digest(persisted))) {
                Arrays.fill(persisted, (byte) 0);
                throw new IOException("SAF document failed length or SHA-256 verification");
            }
            Arrays.fill(persisted, (byte) 0);
            verified = true;
            return new Saved(
                packageValue.reportId(),
                packageValue.packageId(),
                packageValue.revision(),
                packageValue.packageSha256(),
                expected.length
            );
        } finally {
            Arrays.fill(expected, (byte) 0);
            packageValue.destroy();
            if (!verified) {
                try {
                    destination.delete();
                } catch (IOException | RuntimeException ignored) {
                    // Best-effort cleanup: the provider may already have removed the failed document.
                }
            }
        }
    }
}
