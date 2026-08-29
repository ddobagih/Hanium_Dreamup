package kr.co.hanium.dreamup.walksafe.admin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestController;
import kr.co.hanium.dreamup.walksafe.admin.security.AdminReportRequestModels;
import org.junit.Test;

public final class AdminReportRequestRotationStateTest {
    private static final String RESTORED_REQUEST_ID =
        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";

    @Test
    public void rotationSelectionSurvivesLoginIdleRenderWithoutStartingARequest() {
        final int[] networkCalls = {0};
        AdminReportRequestController controller = new AdminReportRequestController(
            new AdminReportRequestController.Loader() {
                @Override
                public AdminReportRequestModels.Page loadPage(
                    AdminReportRequestModels.Filters filters,
                    String cursor
                ) {
                    networkCalls[0] += 1;
                    throw new AssertionError("IDLE render must not load a list");
                }

                @Override
                public AdminReportRequestModels.Detail loadDetail(String requestId) {
                    networkCalls[0] += 1;
                    throw new AssertionError("IDLE render must not load detail");
                }

                @Override
                public AdminReportRequestModels.StatusSnapshot updateStatus(
                    String requestId,
                    String nextStatus,
                    int expectedVersion,
                    String publicResponse,
                    String internalNote,
                    char[] password,
                    char[] totp
                ) {
                    networkCalls[0] += 1;
                    throw new AssertionError("IDLE render must not mutate");
                }
            }
        );

        AdminReportRequestController.State loginRender = controller.snapshot();
        assertNull(loginRender.selectedRequestId());
        assertEquals(
            RESTORED_REQUEST_ID,
            AdminReportRequestPanel.selectionAfterRender(RESTORED_REQUEST_ID, loginRender)
        );
        assertEquals(0, networkCalls[0]);
    }
}
