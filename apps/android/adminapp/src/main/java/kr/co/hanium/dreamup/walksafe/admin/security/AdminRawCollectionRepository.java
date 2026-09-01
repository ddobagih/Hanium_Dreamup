package kr.co.hanium.dreamup.walksafe.admin.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

/** Administrator-only metadata review boundary for quarantined raw collections. */
public interface AdminRawCollectionRepository {
    final class ConflictException extends IOException {
        public ConflictException() {
            super("raw collection review state conflicted");
        }
    }

    AdminRawCollectionModels.Page listQuarantine(
        AdminOperationsApi.SessionContext session,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;

    AdminRawCollectionModels.PurposeDecisionReceipt decidePurpose(
        AdminOperationsApi.SessionContext session,
        AdminRawCollectionModels.Summary source,
        AdminRawCollectionModels.PurposeDecisionRequest request,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;

    AdminRawCollectionModels.LegalHoldReceipt recordLegalHold(
        AdminOperationsApi.SessionContext session,
        String collectionId,
        AdminRawCollectionModels.LegalHoldRequest request,
        Map<String, String> reconfirmationHeaders
    ) throws IOException, GeneralSecurityException;
}
