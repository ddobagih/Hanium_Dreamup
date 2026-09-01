package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict metadata-only projection for quarantined raw collections and their review receipts. */
public final class AdminRawCollectionModels {
    public static final String LIST_SCHEMA = "walksafe.admin-raw-collection-list.v1";
    public static final int MAX_LIST_ITEMS = 100;

    private static final Set<String> PURPOSES = AdminJava8Collections.set(
        "GENERAL_RAW", "AUTO_REPORT"
    );
    private static final Set<String> DECISION_SCOPES = AdminJava8Collections.set(
        "REPORT", "TRAINING"
    );
    private static final Set<String> DECISIONS = AdminJava8Collections.set(
        "APPROVED", "REJECTED"
    );
    private static final Set<String> HOLD_ACTIONS = AdminJava8Collections.set(
        "APPLY", "RELEASE"
    );
    private static final String SHA256_PATTERN = "[0-9a-f]{64}";

    private AdminRawCollectionModels() {}

    public static final class Page {
        private final List<Summary> items;

        private Page(List<Summary> items) {
            this.items = AdminJava8Collections.copyList(items);
        }

        public List<Summary> items() { return items; }
    }

    public static final class Summary {
        private final String collectionId;
        private final String purpose;
        private final String state;
        private final String manifestSha256;
        private final String receiptSha256;
        private final int objectCount;
        private final long totalBytes;
        private final String committedAt;
        private final String quarantineExpiresAt;
        private final String reportDecision;
        private final int reportDecisionRevision;
        private final String trainingDecision;
        private final int trainingDecisionRevision;
        private final boolean legalHoldActive;
        private final int legalHoldRevision;

        private Summary(Map<String, Object> value) throws IOException {
            exact(value, AdminJava8Collections.set(
                "collection_id", "purpose", "state", "manifest_sha256", "receipt_sha256",
                "object_count", "total_bytes", "committed_at", "quarantine_expires_at",
                "report_decision", "report_decision_revision", "training_decision",
                "training_decision_revision", "legal_hold_active", "legal_hold_revision"
            ));
            collectionId = uuid(value, "collection_id");
            purpose = member(value, "purpose", PURPOSES);
            state = text(value, "state", 32);
            if (!"QUARANTINED".equals(state)) {
                throw new IOException("non-quarantined raw collection is forbidden in this projection");
            }
            manifestSha256 = sha256(value, "manifest_sha256", false);
            receiptSha256 = sha256(value, "receipt_sha256", true);
            objectCount = integer(value, "object_count", 0, Integer.MAX_VALUE);
            totalBytes = longInteger(value, "total_bytes", 0L, Long.MAX_VALUE);
            committedAt = nullableInstant(value, "committed_at");
            quarantineExpiresAt = nullableInstant(value, "quarantine_expires_at");
            reportDecision = nullableMember(value, "report_decision", DECISIONS);
            reportDecisionRevision = integer(
                value, "report_decision_revision", 0, Integer.MAX_VALUE
            );
            trainingDecision = nullableMember(value, "training_decision", DECISIONS);
            trainingDecisionRevision = integer(
                value, "training_decision_revision", 0, Integer.MAX_VALUE
            );
            legalHoldActive = bool(value, "legal_hold_active");
            legalHoldRevision = integer(value, "legal_hold_revision", 0, Integer.MAX_VALUE);
            if ((reportDecision == null) != (reportDecisionRevision == 0)
                || (trainingDecision == null) != (trainingDecisionRevision == 0)
                || (legalHoldActive && legalHoldRevision == 0)) {
                throw new IOException("raw collection decision revisions are inconsistent");
            }
        }

        public String collectionId() { return collectionId; }
        public String purpose() { return purpose; }
        public String state() { return state; }
        public String manifestSha256() { return manifestSha256; }
        public String receiptSha256() { return receiptSha256; }
        public int objectCount() { return objectCount; }
        public long totalBytes() { return totalBytes; }
        public String committedAt() { return committedAt; }
        public String quarantineExpiresAt() { return quarantineExpiresAt; }
        public String reportDecision() { return reportDecision; }
        public int reportDecisionRevision() { return reportDecisionRevision; }
        public String trainingDecision() { return trainingDecision; }
        public int trainingDecisionRevision() { return trainingDecisionRevision; }
        public boolean legalHoldActive() { return legalHoldActive; }
        public int legalHoldRevision() { return legalHoldRevision; }

        public int decisionRevision(String scope) {
            if ("REPORT".equals(scope)) return reportDecisionRevision;
            if ("TRAINING".equals(scope)) return trainingDecisionRevision;
            throw new IllegalArgumentException("raw decision scope is invalid");
        }
    }

    public static final class PurposeDecisionRequest {
        private final String scope;
        private final String decision;
        private final int expectedRevision;
        private final String idempotencyKey;
        private final String reason;
        private final String trainingConsentReceiptSha256;
        private final String deidentificationReceiptSha256;
        private final String sanitizedManifestSha256;
        private final String targetDatasetId;
        private final boolean exactLocationExcluded;
        private final boolean rawAudioExcluded;
        private final boolean thirdPartyFacesExcluded;

        public PurposeDecisionRequest(
            String scope,
            String decision,
            int expectedRevision,
            String idempotencyKey,
            String reason,
            String trainingConsentReceiptSha256,
            String deidentificationReceiptSha256,
            String sanitizedManifestSha256,
            String targetDatasetId,
            boolean exactLocationExcluded,
            boolean rawAudioExcluded,
            boolean thirdPartyFacesExcluded
        ) {
            this.scope = inputMember(scope, DECISION_SCOPES, "decision scope");
            this.decision = inputMember(decision, DECISIONS, "decision");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expected decision revision is invalid");
            }
            this.expectedRevision = expectedRevision;
            this.idempotencyKey = canonicalUuid(idempotencyKey, "idempotency_key");
            this.reason = boundedInput(reason, "decision reason", 1, 500);
            boolean trainingApproval = "TRAINING".equals(this.scope)
                && "APPROVED".equals(this.decision);
            if (trainingApproval) {
                this.trainingConsentReceiptSha256 = inputSha256(
                    trainingConsentReceiptSha256, "training consent receipt"
                );
                this.deidentificationReceiptSha256 = inputSha256(
                    deidentificationReceiptSha256, "de-identification receipt"
                );
                this.sanitizedManifestSha256 = inputSha256(
                    sanitizedManifestSha256, "sanitized manifest"
                );
                this.targetDatasetId = canonicalUuid(targetDatasetId, "target_dataset_id");
                if (!exactLocationExcluded || !rawAudioExcluded || !thirdPartyFacesExcluded) {
                    throw new IllegalArgumentException(
                        "training approval requires all exclusion confirmations"
                    );
                }
            } else {
                if (notBlank(trainingConsentReceiptSha256)
                    || notBlank(deidentificationReceiptSha256)
                    || notBlank(sanitizedManifestSha256)
                    || notBlank(targetDatasetId)
                    || exactLocationExcluded || rawAudioExcluded || thirdPartyFacesExcluded) {
                    throw new IllegalArgumentException(
                        "training evidence is exclusive to approved TRAINING decisions"
                    );
                }
                this.trainingConsentReceiptSha256 = null;
                this.deidentificationReceiptSha256 = null;
                this.sanitizedManifestSha256 = null;
                this.targetDatasetId = null;
            }
            this.exactLocationExcluded = exactLocationExcluded;
            this.rawAudioExcluded = rawAudioExcluded;
            this.thirdPartyFacesExcluded = thirdPartyFacesExcluded;
        }

        public String scope() { return scope; }
        public String decision() { return decision; }
        public int expectedRevision() { return expectedRevision; }
        public String idempotencyKey() { return idempotencyKey; }
        public String reason() { return reason; }
        public String trainingConsentReceiptSha256() { return trainingConsentReceiptSha256; }
        public String deidentificationReceiptSha256() { return deidentificationReceiptSha256; }
        public String sanitizedManifestSha256() { return sanitizedManifestSha256; }
        public String targetDatasetId() { return targetDatasetId; }
        public boolean exactLocationExcluded() { return exactLocationExcluded; }
        public boolean rawAudioExcluded() { return rawAudioExcluded; }
        public boolean thirdPartyFacesExcluded() { return thirdPartyFacesExcluded; }
    }

    public static final class PurposeDecisionReceipt {
        private final String collectionId;
        private final String scope;
        private final String decision;
        private final int revision;
        private final String decidedAt;

        private PurposeDecisionReceipt(
            String collectionId,
            String scope,
            String decision,
            int revision,
            String decidedAt
        ) {
            this.collectionId = collectionId;
            this.scope = scope;
            this.decision = decision;
            this.revision = revision;
            this.decidedAt = decidedAt;
        }

        public String collectionId() { return collectionId; }
        public String scope() { return scope; }
        public String decision() { return decision; }
        public int revision() { return revision; }
        public String decidedAt() { return decidedAt; }
    }

    public static final class LegalHoldRequest {
        private final String action;
        private final int expectedRevision;
        private final String idempotencyKey;
        private final String reason;
        private final String legalBasis;
        private final String authorityReference;
        private final String contact;
        private final String expiresAt;

        public LegalHoldRequest(
            String action,
            int expectedRevision,
            String idempotencyKey,
            String reason,
            String legalBasis,
            String authorityReference,
            String contact,
            String expiresAt
        ) {
            this.action = inputMember(action, HOLD_ACTIONS, "legal hold action");
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expected legal hold revision is invalid");
            }
            this.expectedRevision = expectedRevision;
            this.idempotencyKey = canonicalUuid(idempotencyKey, "idempotency_key");
            this.reason = boundedInput(reason, "legal hold reason", 1, 500);
            if ("APPLY".equals(this.action)) {
                this.legalBasis = boundedInput(legalBasis, "legal basis", 1, 500);
                this.authorityReference = boundedInput(
                    authorityReference, "authority reference", 1, 160
                );
                this.contact = boundedInput(contact, "legal hold contact", 1, 160);
                this.expiresAt = inputInstant(expiresAt, "legal hold expiry");
            } else {
                if (notBlank(legalBasis) || notBlank(authorityReference)
                    || notBlank(contact) || notBlank(expiresAt)) {
                    throw new IllegalArgumentException(
                        "legal hold release cannot include apply-only fields"
                    );
                }
                this.legalBasis = null;
                this.authorityReference = null;
                this.contact = null;
                this.expiresAt = null;
            }
        }

        public String action() { return action; }
        public int expectedRevision() { return expectedRevision; }
        public String idempotencyKey() { return idempotencyKey; }
        public String reason() { return reason; }
        public String legalBasis() { return legalBasis; }
        public String authorityReference() { return authorityReference; }
        public String contact() { return contact; }
        public String expiresAt() { return expiresAt; }
    }

    public static final class LegalHoldReceipt {
        private final String collectionId;
        private final String action;
        private final int revision;
        private final String recordedAt;

        private LegalHoldReceipt(
            String collectionId,
            String action,
            int revision,
            String recordedAt
        ) {
            this.collectionId = collectionId;
            this.action = action;
            this.revision = revision;
            this.recordedAt = recordedAt;
        }

        public String collectionId() { return collectionId; }
        public String action() { return action; }
        public int revision() { return revision; }
        public String recordedAt() { return recordedAt; }
    }

    public static Page parsePage(String json) throws IOException {
        Map<String, Object> root = AdminStrictJson.parseObject(json);
        exact(root, AdminJava8Collections.set("schema_version", "items"));
        if (!LIST_SCHEMA.equals(text(root, "schema_version", 64))) {
            throw new IOException("raw collection list schema is unsupported");
        }
        Object rawItems = root.get("items");
        if (!(rawItems instanceof List<?> values) || values.size() > MAX_LIST_ITEMS) {
            throw new IOException("raw collection list size is invalid");
        }
        List<Summary> items = new ArrayList<>(values.size());
        Set<String> ids = new HashSet<>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> object)) {
                throw new IOException("raw collection list item is invalid");
            }
            Summary item = new Summary(stringObject(object));
            if (!ids.add(item.collectionId())) {
                throw new IOException("duplicate raw collection is forbidden");
            }
            items.add(item);
        }
        return new Page(items);
    }

    public static PurposeDecisionReceipt parsePurposeDecisionReceipt(
        String json,
        Summary expectedSource,
        PurposeDecisionRequest request,
        String expectedAdminId
    ) throws IOException {
        if (expectedSource == null) {
            throw new IllegalArgumentException("decision source is required");
        }
        if (request == null) throw new IllegalArgumentException("decision request is required");
        Map<String, Object> value = AdminStrictJson.parseObject(json);
        exact(value, AdminJava8Collections.set(
            "id", "collection_id", "scope", "revision", "expected_revision",
            "idempotency_key", "decision", "reason", "source_manifest_sha256",
            "source_receipt_sha256", "training_consent_receipt_sha256",
            "deidentification_receipt_sha256", "sanitized_manifest_sha256",
            "target_dataset_id", "exact_location_excluded", "raw_audio_excluded",
            "third_party_faces_excluded", "admin_id", "decided_at"
        ));
        uuid(value, "id");
        String collectionId = uuid(value, "collection_id");
        String scope = member(value, "scope", DECISION_SCOPES);
        int expectedRevision = integer(value, "expected_revision", 0, Integer.MAX_VALUE);
        int revision = integer(value, "revision", 1, Integer.MAX_VALUE);
        String idempotencyKey = uuid(value, "idempotency_key");
        String decision = member(value, "decision", DECISIONS);
        String reason = text(value, "reason", 500);
        String sourceManifestSha256 = sha256(value, "source_manifest_sha256", false);
        String sourceReceiptSha256 = sha256(value, "source_receipt_sha256", false);
        String trainingConsent = sha256(value, "training_consent_receipt_sha256", true);
        String deidentification = sha256(value, "deidentification_receipt_sha256", true);
        String sanitized = sha256(value, "sanitized_manifest_sha256", true);
        String targetDataset = nullableUuid(value, "target_dataset_id");
        boolean exactLocationExcluded = bool(value, "exact_location_excluded");
        boolean rawAudioExcluded = bool(value, "raw_audio_excluded");
        boolean thirdPartyFacesExcluded = bool(value, "third_party_faces_excluded");
        String adminId = text(value, "admin_id", 64);
        String decidedAt = instant(value, "decided_at");
        if (!expectedSource.collectionId().equals(collectionId)
            || !expectedSource.manifestSha256().equals(sourceManifestSha256)
            || !same(expectedSource.receiptSha256(), sourceReceiptSha256)
            || !request.scope().equals(scope)
            || request.expectedRevision() != expectedRevision
            || revision != expectedRevision + 1
            || !request.idempotencyKey().equals(idempotencyKey)
            || !request.decision().equals(decision)
            || !request.reason().equals(reason)
            || !same(request.trainingConsentReceiptSha256(), trainingConsent)
            || !same(request.deidentificationReceiptSha256(), deidentification)
            || !same(request.sanitizedManifestSha256(), sanitized)
            || !same(request.targetDatasetId(), targetDataset)
            || request.exactLocationExcluded() != exactLocationExcluded
            || request.rawAudioExcluded() != rawAudioExcluded
            || request.thirdPartyFacesExcluded() != thirdPartyFacesExcluded
            || !requiredAdminId(expectedAdminId).equals(adminId)) {
            throw new IOException("raw purpose decision receipt binding is invalid");
        }
        return new PurposeDecisionReceipt(collectionId, scope, decision, revision, decidedAt);
    }

    public static LegalHoldReceipt parseLegalHoldReceipt(
        String json,
        String expectedCollectionId,
        LegalHoldRequest request,
        String expectedAdminId
    ) throws IOException {
        if (request == null) throw new IllegalArgumentException("legal hold request is required");
        Map<String, Object> value = AdminStrictJson.parseObject(json);
        exact(value, AdminJava8Collections.set(
            "id", "collection_id", "revision", "expected_revision", "idempotency_key",
            "action", "reason", "legal_basis", "authority_reference", "contact",
            "expires_at", "admin_id", "recorded_at"
        ));
        uuid(value, "id");
        String collectionId = uuid(value, "collection_id");
        int expectedRevision = integer(value, "expected_revision", 0, Integer.MAX_VALUE);
        int revision = integer(value, "revision", 1, Integer.MAX_VALUE);
        String idempotencyKey = uuid(value, "idempotency_key");
        String action = member(value, "action", HOLD_ACTIONS);
        String reason = text(value, "reason", 500);
        String legalBasis = nullableText(value, "legal_basis", 500);
        String authorityReference = nullableText(value, "authority_reference", 160);
        String contact = nullableText(value, "contact", 160);
        String expiresAt = nullableInstant(value, "expires_at");
        String adminId = text(value, "admin_id", 64);
        String recordedAt = instant(value, "recorded_at");
        if (!canonicalUuid(expectedCollectionId, "collection_id").equals(collectionId)
            || request.expectedRevision() != expectedRevision
            || revision != expectedRevision + 1
            || !request.idempotencyKey().equals(idempotencyKey)
            || !request.action().equals(action)
            || !request.reason().equals(reason)
            || !same(request.legalBasis(), legalBasis)
            || !same(request.authorityReference(), authorityReference)
            || !same(request.contact(), contact)
            || !sameInstant(request.expiresAt(), expiresAt)
            || !requiredAdminId(expectedAdminId).equals(adminId)) {
            throw new IOException("raw legal hold receipt binding is invalid");
        }
        return new LegalHoldReceipt(collectionId, action, revision, recordedAt);
    }

    public static String canonicalUuid(String value, String label) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException(label + " is not canonical");
            }
            return value;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(label + " is not a canonical UUID", error);
        }
    }

    private static String requiredAdminId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}")) {
            throw new IllegalArgumentException("administrator identity is invalid");
        }
        return value;
    }

    private static String inputMember(String value, Set<String> allowed, String label) {
        if (value == null || !allowed.contains(value)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return value;
    }

    private static String boundedInput(String value, String label, int min, int max) {
        if (value == null) throw new IllegalArgumentException(label + " is required");
        String normalized = value.trim();
        if (normalized.length() < min || normalized.length() > max || hasControl(normalized)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static String inputSha256(String value, String label) {
        String normalized = boundedInput(value, label, 64, 64);
        if (!normalized.matches(SHA256_PATTERN)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }

    private static String inputInstant(String value, String label) {
        String normalized = boundedInput(value, label, 1, 64);
        try {
            OffsetDateTime.parse(normalized);
            return normalized;
        } catch (DateTimeException error) {
            throw new IllegalArgumentException(label + " is invalid", error);
        }
    }

    private static void exact(Map<String, Object> value, Set<String> keys) throws IOException {
        if (!value.keySet().equals(keys)) {
            throw new IOException("raw collection fields do not match the contract");
        }
    }

    private static String text(Map<String, Object> value, String key, int max) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof String text) || text.trim().isEmpty()
            || text.length() > max || hasControl(text)) {
            throw new IOException("raw collection text is invalid: " + key);
        }
        return text;
    }

    private static String nullableText(
        Map<String, Object> value,
        String key,
        int max
    ) throws IOException {
        return value.get(key) == null ? null : text(value, key, max);
    }

    private static String member(
        Map<String, Object> value,
        String key,
        Set<String> allowed
    ) throws IOException {
        String parsed = text(value, key, 64);
        if (!allowed.contains(parsed)) {
            throw new IOException("raw collection enum is invalid: " + key);
        }
        return parsed;
    }

    private static String nullableMember(
        Map<String, Object> value,
        String key,
        Set<String> allowed
    ) throws IOException {
        String parsed = nullableText(value, key, 64);
        if (parsed != null && !allowed.contains(parsed)) {
            throw new IOException("raw collection enum is invalid: " + key);
        }
        return parsed;
    }

    private static String uuid(Map<String, Object> value, String key) throws IOException {
        try {
            return canonicalUuid(text(value, key, 36), key);
        } catch (IllegalArgumentException error) {
            throw new IOException("raw collection UUID is invalid: " + key, error);
        }
    }

    private static String nullableUuid(Map<String, Object> value, String key) throws IOException {
        if (value.get(key) == null) return null;
        return uuid(value, key);
    }

    private static String sha256(
        Map<String, Object> value,
        String key,
        boolean nullable
    ) throws IOException {
        String parsed = nullable ? nullableText(value, key, 64) : text(value, key, 64);
        if (parsed != null && !parsed.matches(SHA256_PATTERN)) {
            throw new IOException("raw collection digest is invalid: " + key);
        }
        return parsed;
    }

    private static int integer(
        Map<String, Object> value,
        String key,
        int min,
        int max
    ) throws IOException {
        long number = longInteger(value, key, min, max);
        return (int) number;
    }

    private static long longInteger(
        Map<String, Object> value,
        String key,
        long min,
        long max
    ) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Long number) || number < min || number > max) {
            throw new IOException("raw collection integer is invalid: " + key);
        }
        return number;
    }

    private static boolean bool(Map<String, Object> value, String key) throws IOException {
        Object raw = value.get(key);
        if (!(raw instanceof Boolean parsed)) {
            throw new IOException("raw collection boolean is invalid: " + key);
        }
        return parsed;
    }

    private static String instant(Map<String, Object> value, String key) throws IOException {
        String parsed = text(value, key, 64);
        try {
            OffsetDateTime.parse(parsed);
            return parsed;
        } catch (DateTimeException error) {
            throw new IOException("raw collection timestamp is invalid: " + key, error);
        }
    }

    private static String nullableInstant(Map<String, Object> value, String key) throws IOException {
        return value.get(key) == null ? null : instant(value, key);
    }

    private static Map<String, Object> stringObject(Map<?, ?> value) throws IOException {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("raw collection object key is invalid");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static boolean same(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static boolean sameInstant(String left, String right) {
        if (left == null || right == null) return left == null && right == null;
        try {
            return OffsetDateTime.parse(left).toInstant().equals(
                OffsetDateTime.parse(right).toInstant()
            );
        } catch (DateTimeException error) {
            return false;
        }
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }
}
