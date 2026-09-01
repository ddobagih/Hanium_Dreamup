package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Verified delivery package held only in memory until the user chooses a SAF document. */
public final class AdminDeliveryPackage {
    public static final String PACKAGE_SCHEMA = "walksafe.admin-report-delivery-package.v2";
    public static final String PROOF_SCHEMA = "walksafe.admin-report-delivery-package-proof.v1";
    private static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    private static final Set<String> MANIFEST_FIELDS = AdminJava8Collections.set(
        "category_hint", "content_revision", "content_sha256", "csv_bytes", "csv_name",
        "csv_sha256", "export_audit_id", "package_revision", "package_version", "report_id",
        "review_decision_id", "review_revision", "row_count", "schema_version",
        "supersedes_package_id", "user_description"
    );
    private static final Set<String> PROOF_FIELDS = AdminJava8Collections.set(
        "schema_version", "package_revision", "content_revision", "review_revision",
        "package_schema_version", "package_byte_count", "package_sha256"
    );

    /** Exact package-creation eligibility captured from the detail used to start the request. */
    public static final class Eligibility {
        private final String reportId;
        private final int contentRevision;
        private final int reviewRevision;
        private final int latestDeliveryRevision;
        private final String reportStatus;
        private final int statusVersion;
        private final String deliveryFingerprint;

        private Eligibility(AdminReportModels.Detail detail) {
            reportId = detail.summary().id();
            contentRevision = detail.contentRevision();
            reviewRevision = detail.review().revision();
            latestDeliveryRevision = detail.latestDeliveryRevision();
            reportStatus = detail.summary().status();
            statusVersion = detail.summary().statusVersion();
            deliveryFingerprint = fingerprint(detail.delivery());
        }

        public static Eligibility fromDetail(AdminReportModels.Detail detail) {
            if (!isPackageEligible(detail)) {
                throw new IllegalArgumentException(
                    "the latest report content does not have an eligible approved review"
                );
            }
            return new Eligibility(detail);
        }

        public String reportId() { return reportId; }
        public int contentRevision() { return contentRevision; }
        public int reviewRevision() { return reviewRevision; }
        public int latestDeliveryRevision() { return latestDeliveryRevision; }

        public boolean matchesExact(AdminReportModels.Detail detail) {
            return isPackageEligible(detail)
                && reportId.equals(detail.summary().id())
                && contentRevision == detail.contentRevision()
                && reviewRevision == detail.review().revision()
                && latestDeliveryRevision == detail.latestDeliveryRevision()
                && reportStatus.equals(detail.summary().status())
                && statusVersion == detail.summary().statusVersion()
                && java.util.Objects.equals(deliveryFingerprint, fingerprint(detail.delivery()));
        }

        private static boolean isPackageEligible(AdminReportModels.Detail detail) {
            if (detail == null || detail.review() == null
                || !"APPROVED".equals(detail.review().decision())
                || !detail.review().locationReviewed()
                || !detail.review().photoReviewed()
                || !detail.review().privacyReviewed()
                || detail.review().duplicateOfReportId() != null) {
                return false;
            }
            return detail.delivery() == null || !"RESOLVED".equals(detail.delivery().status());
        }

        private static String fingerprint(AdminReportModels.DeliverySummary delivery) {
            if (delivery == null) return null;
            return delivery.revision() + ":" + delivery.packageId() + ":"
                + delivery.packageRevision() + ":" + delivery.packageContentRevision() + ":"
                + delivery.packageSchemaVersion() + ":" + delivery.packageVersion() + ":"
                + delivery.exportAuditId() + ":" + delivery.packageSha256() + ":"
                + delivery.csvSha256() + ":" + delivery.manifestSha256() + ":"
                + delivery.packageByteCount() + ":" + delivery.status();
        }
    }

    /** Untrusted reference used only to select the exact server proof resource. */
    public static final class Reference {
        private final String reportId;
        private final int packageRevision;

        private Reference(String reportId, int packageRevision) {
            this.reportId = reportId;
            this.packageRevision = packageRevision;
        }

        public String reportId() { return reportId; }
        public int packageRevision() { return packageRevision; }
    }

    /** Minimal trusted package metadata returned by the exact package-proof endpoint. */
    public static final class Proof {
        private final String reportId;
        private final int packageRevision;
        private final int contentRevision;
        private final int reviewRevision;
        private final int byteCount;
        private final String packageSha256;

        private Proof(
            String reportId,
            int packageRevision,
            int contentRevision,
            int reviewRevision,
            int byteCount,
            String packageSha256
        ) {
            this.reportId = reportId;
            this.packageRevision = packageRevision;
            this.contentRevision = contentRevision;
            this.reviewRevision = reviewRevision;
            this.byteCount = byteCount;
            this.packageSha256 = packageSha256;
        }

        public String reportId() { return reportId; }
        public int packageRevision() { return packageRevision; }
        public int contentRevision() { return contentRevision; }
        public int reviewRevision() { return reviewRevision; }
        public int byteCount() { return byteCount; }
        public String packageSha256() { return packageSha256; }

        public boolean matchesReference(Reference reference) {
            return reference != null
                && reportId.equals(reference.reportId())
                && packageRevision == reference.packageRevision();
        }

        public boolean matchesFreshDetail(AdminReportModels.Detail detail) {
            if (!Eligibility.isPackageEligible(detail)) return false;
            return reportId.equals(detail.summary().id())
                && contentRevision == detail.contentRevision()
                && reviewRevision == detail.review().revision();
        }
    }

    static final class VerifiedManifest {
        final String reportId;
        final int packageRevision;
        final int contentRevision;
        final int reviewRevision;
        final String exportAuditId;
        final String csvSha256;
        final String manifestSha256;

        private VerifiedManifest(
            String reportId,
            int packageRevision,
            int contentRevision,
            int reviewRevision,
            String exportAuditId,
            String csvSha256,
            String manifestSha256
        ) {
            this.reportId = reportId;
            this.packageRevision = packageRevision;
            this.contentRevision = contentRevision;
            this.reviewRevision = reviewRevision;
            this.exportAuditId = exportAuditId;
            this.csvSha256 = csvSha256;
            this.manifestSha256 = manifestSha256;
        }
    }

    private final String reportId;
    private final String packageId;
    private final int revision;
    private final int contentRevision;
    private final int reviewRevision;
    private final String exportAuditId;
    private final String packageSha256;
    private final String csvSha256;
    private final String manifestSha256;
    private final int expectedByteCount;
    private final Eligibility eligibility;
    private byte[] bytes;

    public AdminDeliveryPackage(
        String reportId,
        String packageId,
        int revision,
        int contentRevision,
        int reviewRevision,
        String exportAuditId,
        String packageSha256,
        String csvSha256,
        String manifestSha256,
        int expectedByteCount,
        Eligibility eligibility,
        byte[] bytes
    ) throws IOException {
        this.reportId = AdminReportModels.canonicalUuid(reportId, "report_id");
        this.packageId = AdminReportModels.canonicalUuid(packageId, "package_id");
        if (revision < 1) throw new IllegalArgumentException("package revision must be positive");
        this.revision = revision;
        if (contentRevision < 0) throw new IllegalArgumentException("content revision must not be negative");
        this.contentRevision = contentRevision;
        if (reviewRevision < 1) throw new IllegalArgumentException("review revision must be positive");
        this.reviewRevision = reviewRevision;
        this.exportAuditId = AdminReportModels.canonicalUuid(exportAuditId, "export_audit_id");
        this.packageSha256 = sha(packageSha256, "package_sha256");
        this.csvSha256 = sha(csvSha256, "csv_sha256");
        this.manifestSha256 = sha(manifestSha256, "manifest_sha256");
        if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024
            || expectedByteCount != bytes.length) {
            throw new IOException("delivery package size is invalid");
        }
        this.expectedByteCount = expectedByteCount;
        if (eligibility == null
            || !this.reportId.equals(eligibility.reportId())
            || contentRevision != eligibility.contentRevision()
            || reviewRevision != eligibility.reviewRevision()) {
            throw new IOException("delivery package does not match its starting detail");
        }
        this.eligibility = eligibility;
        this.bytes = bytes.clone();
        if (!this.packageSha256.equals(digest(this.bytes))) {
            destroy();
            throw new IOException("delivery package digest is invalid");
        }
        try {
            VerifiedManifest manifest = readManifest(this.bytes);
            requireManifestMatches(
                manifest, this.reportId, revision, contentRevision, reviewRevision,
                this.exportAuditId, this.csvSha256, this.manifestSha256
            );
        } catch (IOException error) {
            destroy();
            throw error;
        }
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
    public int expectedByteCount() { return expectedByteCount; }
    public boolean matchesFreshEligibility(AdminReportModels.Detail detail) {
        return eligibility.matchesExact(detail);
    }
    public synchronized int byteCount() { return bytes == null ? 0 : bytes.length; }
    public synchronized byte[] copyBytes() throws IOException {
        if (bytes == null) throw new IOException("delivery package is no longer available");
        return bytes.clone();
    }
    public synchronized void destroy() {
        if (bytes != null) Arrays.fill(bytes, (byte) 0);
        bytes = null;
    }

    public static Proof parseProof(
        String body,
        String expectedReportId,
        int expectedPackageRevision
    ) throws IOException {
        String reportId = AdminReportModels.canonicalUuid(expectedReportId, "report_id");
        if (expectedPackageRevision < 1) {
            throw new IllegalArgumentException("package revision must be positive");
        }
        Map<String, Object> root = AdminStrictJson.parseObject(body);
        if (!root.keySet().equals(PROOF_FIELDS)
            || !PROOF_SCHEMA.equals(requiredString(root, "schema_version", 64))
            || !PACKAGE_SCHEMA.equals(requiredString(root, "package_schema_version", 64))) {
            throw new IOException("delivery package proof fields do not match contract");
        }
        int revision = requiredInt(root, "package_revision", 1, Integer.MAX_VALUE);
        if (revision != expectedPackageRevision) {
            throw new IOException("delivery package proof revision is mismatched");
        }
        return new Proof(
            reportId,
            revision,
            requiredInt(root, "content_revision", 0, Integer.MAX_VALUE),
            requiredInt(root, "review_revision", 1, Integer.MAX_VALUE),
            requiredInt(root, "package_byte_count", 1, 8 * 1024 * 1024),
            requiredSha(root, "package_sha256")
        );
    }

    static Reference inspectReference(byte[] packageBytes) throws IOException {
        VerifiedManifest manifest = readManifest(packageBytes);
        return new Reference(manifest.reportId, manifest.packageRevision);
    }

    static VerifiedManifest verifyAgainstProof(Proof proof, byte[] packageBytes) throws IOException {
        if (proof == null || packageBytes == null
            || packageBytes.length != proof.byteCount
            || !proof.packageSha256.equals(digest(packageBytes))) {
            throw new IOException("existing SAF package failed server length or SHA-256 verification");
        }
        VerifiedManifest manifest = readManifest(packageBytes);
        requireManifestMatches(
            manifest, proof.reportId, proof.packageRevision, proof.contentRevision,
            proof.reviewRevision, null, null, null
        );
        return manifest;
    }

    private static VerifiedManifest readManifest(byte[] packageBytes) throws IOException {
        if (packageBytes == null || packageBytes.length < 1 || packageBytes.length > 8 * 1024 * 1024) {
            throw new IOException("delivery package size is invalid");
        }
        Set<String> names = new HashSet<>();
        byte[] csvBytes = null;
        byte[] manifestBytes = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            try {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || !names.add(name)
                        || !("report.csv".equals(name) || "manifest.json".equals(name))) {
                        throw new IOException("delivery package entries are invalid");
                    }
                    byte[] entryBytes = readEntry(zip);
                    if ("report.csv".equals(name)) csvBytes = entryBytes;
                    else manifestBytes = entryBytes;
                }
                if (!names.equals(AdminJava8Collections.set("report.csv", "manifest.json"))
                    || csvBytes == null || manifestBytes == null || csvBytes.length < 1) {
                    throw new IOException("delivery package entries are incomplete");
                }
                Map<String, Object> manifest = AdminStrictJson.parseObject(
                    AdminStrictJson.decodeUtf8(manifestBytes)
                );
                if (!manifest.keySet().equals(MANIFEST_FIELDS)
                    || !PACKAGE_SCHEMA.equals(requiredString(manifest, "schema_version", 64))
                    || requiredInt(manifest, "package_version", 2, 2) != 2
                    || !"report.csv".equals(requiredString(manifest, "csv_name", 64))
                    || csvBytes.length != requiredInt(manifest, "csv_bytes", 1, MAX_ENTRY_BYTES)
                    || requiredInt(manifest, "row_count", 1, 1) != 1) {
                    throw new IOException("delivery package manifest is invalid");
                }
                String csvSha256 = requiredSha(manifest, "csv_sha256");
                if (!csvSha256.equals(digest(csvBytes))) {
                    throw new IOException("delivery package CSV digest is invalid");
                }
                requiredUuid(manifest, "review_decision_id");
                requiredSha(manifest, "content_sha256");
                nullableUuid(manifest.get("supersedes_package_id"), "supersedes_package_id");
                nullableString(manifest.get("user_description"), "user_description", 500);
                nullableCategory(manifest.get("category_hint"));
                return new VerifiedManifest(
                    requiredUuid(manifest, "report_id"),
                    requiredInt(manifest, "package_revision", 1, Integer.MAX_VALUE),
                    requiredInt(manifest, "content_revision", 0, Integer.MAX_VALUE),
                    requiredInt(manifest, "review_revision", 1, Integer.MAX_VALUE),
                    requiredUuid(manifest, "export_audit_id"),
                    csvSha256,
                    digest(manifestBytes)
                );
            } finally {
                if (csvBytes != null) Arrays.fill(csvBytes, (byte) 0);
                if (manifestBytes != null) Arrays.fill(manifestBytes, (byte) 0);
            }
        }
    }

    private static byte[] readEntry(ZipInputStream zip) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4_096];
        int zeroReads = 0;
        try {
            while (true) {
                requireActiveRead();
                int read = zip.read(buffer);
                if (read < 0) break;
                if (read == 0) {
                    if (++zeroReads > 3) {
                        throw new IOException("delivery package entry made no progress");
                    }
                    continue;
                }
                zeroReads = 0;
                if (output.size() + read > MAX_ENTRY_BYTES) {
                    throw new IOException("delivery package entry is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    private static void requireManifestMatches(
        VerifiedManifest manifest,
        String reportId,
        int packageRevision,
        int contentRevision,
        int reviewRevision,
        String exportAuditId,
        String csvSha256,
        String manifestSha256
    ) throws IOException {
        if (!reportId.equals(manifest.reportId)
            || packageRevision != manifest.packageRevision
            || contentRevision != manifest.contentRevision
            || reviewRevision != manifest.reviewRevision
            || (exportAuditId != null && !exportAuditId.equals(manifest.exportAuditId))
            || (csvSha256 != null && !csvSha256.equals(manifest.csvSha256))
            || (manifestSha256 != null && !manifestSha256.equals(manifest.manifestSha256))) {
            throw new IOException("delivery package manifest does not match trusted metadata");
        }
    }

    private static String requiredString(
        Map<String, Object> object,
        String name,
        int maximumCodePoints
    ) throws IOException {
        Object value = object.get(name);
        if (!(value instanceof String text) || text.isEmpty()
            || text.codePointCount(0, text.length()) > maximumCodePoints
            || text.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("delivery package field is invalid: " + name);
        }
        return text;
    }

    private static int requiredInt(
        Map<String, Object> object,
        String name,
        int minimum,
        int maximum
    ) throws IOException {
        Object value = object.get(name);
        if (!(value instanceof Long number) || number < minimum || number > maximum) {
            throw new IOException("delivery package field is invalid: " + name);
        }
        return number.intValue();
    }

    private static String requiredUuid(Map<String, Object> object, String name) throws IOException {
        try {
            return AdminReportModels.canonicalUuid(requiredString(object, name, 36), name);
        } catch (IllegalArgumentException error) {
            throw new IOException("delivery package field is invalid: " + name, error);
        }
    }

    private static String requiredSha(Map<String, Object> object, String name) throws IOException {
        String value = requiredString(object, name, 64);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException("delivery package field is invalid: " + name);
        }
        return value;
    }

    private static void nullableUuid(Object value, String name) throws IOException {
        if (value == null) return;
        if (!(value instanceof String text)) {
            throw new IOException("delivery package field is invalid: " + name);
        }
        try {
            AdminReportModels.canonicalUuid(text, name);
        } catch (IllegalArgumentException error) {
            throw new IOException("delivery package field is invalid: " + name, error);
        }
    }

    private static void nullableString(Object value, String name, int maximum) throws IOException {
        if (value == null) return;
        if (!(value instanceof String text) || text.isEmpty()
            || text.codePointCount(0, text.length()) > maximum) {
            throw new IOException("delivery package field is invalid: " + name);
        }
    }

    private static void nullableCategory(Object value) throws IOException {
        if (value == null) return;
        if (!(value instanceof String category) || !AdminJava8Collections.set(
            "SIDEWALK_OBSTRUCTION", "ROAD_DAMAGE", "ACCESSIBILITY_BARRIER", "OTHER"
        ).contains(category)) {
            throw new IOException("delivery package field is invalid: category_hint");
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
            Arrays.fill(digest, (byte) 0);
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

    private static void requireActiveRead() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("delivery package verification was cancelled");
        }
    }
}
