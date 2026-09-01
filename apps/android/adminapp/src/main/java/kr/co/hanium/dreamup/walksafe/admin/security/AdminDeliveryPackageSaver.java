package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Writes only to a user-selected document and verifies the complete persisted bytes. */
public final class AdminDeliveryPackageSaver {
    private static final int MAX_PACKAGE_BYTES = 8 * 1024 * 1024;

    public interface Destination {
        OutputStream openOutput() throws IOException;
        InputStream openInput() throws IOException;
        void delete() throws IOException;
    }

    public static final class Saved {
        private final String reportId;
        private final String packageId;
        private final int revision;
        private final int contentRevision;
        private final int reviewRevision;
        private final String exportAuditId;
        private final String packageSha256;
        private final String csvSha256;
        private final String manifestSha256;
        private final int byteCount;

        private Saved(
            String reportId,
            String packageId,
            int revision,
            int contentRevision,
            int reviewRevision,
            String exportAuditId,
            String packageSha256,
            String csvSha256,
            String manifestSha256,
            int byteCount
        ) {
            this.reportId = reportId;
            this.packageId = packageId;
            this.revision = revision;
            this.contentRevision = contentRevision;
            this.reviewRevision = reviewRevision;
            this.exportAuditId = exportAuditId;
            this.packageSha256 = packageSha256;
            this.csvSha256 = csvSha256;
            this.manifestSha256 = manifestSha256;
            this.byteCount = byteCount;
        }

        public String reportId() { return reportId; }
        public String packageId() { return packageId; }
        public int revision() { return revision; }
        public int contentRevision() { return contentRevision; }
        public int reviewRevision() { return reviewRevision; }
        public String exportAuditId() { return exportAuditId; }
        public String packageSha256() { return packageSha256; }
        public String csvSha256() { return csvSha256; }
        public String manifestSha256() { return manifestSha256; }
        public int byteCount() { return byteCount; }

        public boolean matchesDelivery(
            String expectedReportId,
            long expectedRevision,
            long expectedContentRevision
        ) {
            return reportId.equals(expectedReportId)
                && revision == expectedRevision
                && contentRevision == expectedContentRevision;
        }

        public boolean matchesProof(AdminDeliveryPackage.Proof proof) {
            return proof != null
                && reportId.equals(proof.reportId())
                && revision == proof.packageRevision()
                && contentRevision == proof.contentRevision()
                && reviewRevision == proof.reviewRevision()
                && byteCount == proof.byteCount()
                && packageSha256.equals(proof.packageSha256());
        }

        public boolean matchesFreshDetail(AdminReportModels.Detail detail) {
            return detail != null
                && detail.review() != null
                && reportId.equals(detail.summary().id())
                && contentRevision == detail.contentRevision()
                && reviewRevision == detail.review().revision()
                && "APPROVED".equals(detail.review().decision())
                && (detail.delivery() == null
                    || !"RESOLVED".equals(detail.delivery().status()));
        }

        public boolean matchesCurrentDelivery(AdminReportModels.Detail detail) {
            if (!hasReusablePackageBinding(detail) || !matchesFreshDetail(detail)) return false;
            AdminReportModels.DeliverySummary delivery = detail.delivery();
            return revision == delivery.packageRevision()
                && contentRevision == delivery.packageContentRevision()
                && delivery.packageVersion() == 2
                && AdminDeliveryPackage.PACKAGE_SCHEMA.equals(delivery.packageSchemaVersion())
                && (packageId == null || packageId.equals(delivery.packageId()))
                && exportAuditId.equals(delivery.exportAuditId())
                && packageSha256.equals(delivery.packageSha256())
                && csvSha256.equals(delivery.csvSha256())
                && manifestSha256.equals(delivery.manifestSha256())
                && byteCount == delivery.packageByteCount();
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
                    requireActive();
                    int count = Math.min(16 * 1024, expected.length - offset);
                    output.write(expected, offset, count);
                    offset += count;
                }
                output.flush();
            }
            byte[] persisted;
            try (InputStream input = destination.openInput()) {
                if (input == null) throw new IOException("SAF verification stream is unavailable");
                persisted = readExactly(input, expected.length);
            }
            try {
                if (!packageValue.packageSha256().equals(AdminDeliveryPackage.digest(persisted))) {
                    throw new IOException("SAF document failed length or SHA-256 verification");
                }
            } finally {
                Arrays.fill(persisted, (byte) 0);
            }
            verified = true;
            return new Saved(
                packageValue.reportId(),
                packageValue.packageId(),
                packageValue.revision(),
                packageValue.contentRevision(),
                packageValue.reviewRevision(),
                packageValue.exportAuditId(),
                packageValue.packageSha256(),
                packageValue.csvSha256(),
                packageValue.manifestSha256(),
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

    /** Parses only an untrusted report/revision locator from a bounded, strict v2 ZIP. */
    public static AdminDeliveryPackage.Reference inspectUntrusted(InputStream input) throws IOException {
        if (input == null) throw new IllegalArgumentException("SAF input is required");
        byte[] bytes = readBounded(input);
        try {
            return AdminDeliveryPackage.inspectReference(bytes);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** Revalidates a selected SAF ZIP against exact trusted server proof metadata. */
    public static Saved verifyExisting(
        AdminDeliveryPackage.Proof proof,
        InputStream input
    ) throws IOException {
        if (proof == null || input == null) {
            throw new IllegalArgumentException("server package proof and SAF input are required");
        }
        byte[] bytes;
        try (InputStream source = input) {
            bytes = readExactly(source, proof.byteCount());
        }
        try {
            AdminDeliveryPackage.VerifiedManifest manifest =
                AdminDeliveryPackage.verifyAgainstProof(proof, bytes);
            return new Saved(
                proof.reportId(),
                null,
                proof.packageRevision(),
                proof.contentRevision(),
                proof.reviewRevision(),
                manifest.exportAuditId,
                proof.packageSha256(),
                manifest.csvSha256,
                manifest.manifestSha256,
                proof.byteCount()
            );
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int zeroReads = 0;
            try {
                while (true) {
                    requireActive();
                    int read = source.read(buffer);
                    if (read < 0) break;
                    if (read == 0) {
                        if (++zeroReads > 3) {
                            throw new IOException("SAF package stream made no progress");
                        }
                        continue;
                    }
                    zeroReads = 0;
                    if (output.size() + read > MAX_PACKAGE_BYTES) {
                        throw new IOException("SAF package is too large");
                    }
                    output.write(buffer, 0, read);
                }
                if (output.size() < 1) throw new IOException("SAF package is empty");
                return output.toByteArray();
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        }
    }

    private static byte[] readExactly(InputStream source, int byteCount) throws IOException {
        if (byteCount < 1 || byteCount > MAX_PACKAGE_BYTES) {
            throw new IOException("SAF package length is invalid");
        }
        byte[] bytes = new byte[byteCount];
        try {
            int offset = 0;
            int zeroReads = 0;
            while (offset < bytes.length) {
                requireActive();
                int read = source.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IOException("SAF package length is invalid");
                if (read == 0) {
                    if (++zeroReads > 3) {
                        throw new IOException("SAF package stream made no progress");
                    }
                    continue;
                }
                zeroReads = 0;
                offset += read;
            }
            requireActive();
            if (source.read() >= 0) throw new IOException("SAF package length is invalid");
            return bytes;
        } catch (IOException | RuntimeException error) {
            Arrays.fill(bytes, (byte) 0);
            throw error;
        }
    }

    private static boolean hasReusablePackageBinding(AdminReportModels.Detail detail) {
        if (detail == null || detail.delivery() == null) return false;
        String status = detail.delivery().status();
        return "FAILED".equals(status)
            || "SUBMITTED".equals(status)
            || "ACKNOWLEDGED".equals(status);
    }

    private static void requireActive() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("SAF delivery package operation was cancelled");
        }
    }
}
