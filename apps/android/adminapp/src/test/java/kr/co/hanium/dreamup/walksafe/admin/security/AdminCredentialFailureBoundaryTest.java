package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.concurrent.Callable;
import org.junit.Test;

public final class AdminCredentialFailureBoundaryTest {
    @Test public void everyCredentialOperationScrubsBuffersWhenItsAuthorityIsAlreadyClosed() throws Exception {
        AdminSecurityApi api = (AdminSecurityApi) Proxy.newProxyInstance(
            AdminSecurityApi.class.getClassLoader(), new Class<?>[]{AdminSecurityApi.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "bindRequestAdmission": case "clearLocalBinding": case "cancelInFlightRequests": return null;
                    default: throw new AssertionError("closed owner must make no API call: " + method.getName());
                }
            });
        AdminSecurityController controller = new AdminSecurityController(api);
        controller.close();
        for (int path = 0; path < 10; path++) {
            char[] password = "synthetic-password".toCharArray(), totp = "123456".toCharArray();
            final int selected = path;
            Callable<?> operation = () -> {
                switch (selected) {
                    case 0: return controller.reports().updateAdminReportStatus(null, "reviewed", 1, password, totp, 1L, true);
                    case 1: return controller.reports().updateAdminReportStatus(null, "reviewed", 1, password, totp, 1L, true, () -> true);
                    case 2: return controller.reports().updateAdminReportRequestStatus(null, "CORRECTION", "ACKNOWLEDGED", 1, null, null, password, totp, 1L, true);
                    case 3: return controller.reports().createAdminDeliveryPackage(null, password, totp, 1L, true);
                    case 4: return controller.incidents().updateAdminIncidentStatus(null, null, password, totp, 1L, true);
                    case 5: return controller.rawCollections().listAdminRawQuarantine(password, totp, 1L, true);
                    case 6: return controller.rawCollections().decideAdminRawCollectionPurpose(null, null, password, totp, 1L, true);
                    case 7: return controller.rawCollections().recordAdminRawCollectionLegalHold(null, null, password, totp, 1L, true);
                    case 8: return controller.deliveries().correctDelivery(null, null, password, totp, 1L, false, true);
                    default: return controller.deliveries().loadAdminOriginalEvidence(null, 1, null, password, totp, 1L, true);
                }
            };
            assertThrows("credential path " + selected, IllegalStateException.class, operation::call);
            assertArrayEquals(new char[password.length], password);
            assertArrayEquals(new char[totp.length], totp);
        }
    }
}
