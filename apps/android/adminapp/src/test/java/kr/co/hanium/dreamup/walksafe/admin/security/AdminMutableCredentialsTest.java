package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.fail;

import java.util.Arrays;
import org.junit.Test;

public final class AdminMutableCredentialsTest {
    @Test
    public void dispatchClearsMutableBuffersAndCannotDispatchTwice() {
        char[] password = "password-secret".toCharArray();
        char[] totp = "123456".toCharArray();
        AdminMutableCredentials credentials = new AdminMutableCredentials(password, totp);

        char[][] dispatched = credentials.takeForDispatch();
        try {
            // S18-20: the dispatch hands over caller-owned char[] copies, not Strings.
            assertArrayEquals("password-secret".toCharArray(), dispatched[0]);
            assertArrayEquals("123456".toCharArray(), dispatched[1]);
            assertNotSame(password, dispatched[0]);
            assertNotSame(totp, dispatched[1]);
            assertArrayEquals(new char[password.length], password);
            assertArrayEquals(new char[totp.length], totp);
            expectDiscarded(credentials);
        } finally {
            Arrays.fill(dispatched[0], '\0');
            Arrays.fill(dispatched[1], '\0');
        }
    }

    @Test
    public void cancelledQueuedCredentialsCannotBeDispatched() {
        char[] recoveryCode = "recovery-code-secret".toCharArray();
        AdminMutableCredentials credentials = new AdminMutableCredentials(recoveryCode, new char[0]);

        credentials.close();
        credentials.close();

        assertArrayEquals(new char[recoveryCode.length], recoveryCode);
        expectDiscarded(credentials);
    }

    private static void expectDiscarded(AdminMutableCredentials credentials) {
        try {
            credentials.takeForDispatch();
            fail("discarded credentials must not be dispatched");
        } catch (IllegalStateException expected) {
            assertEquals("credentials were discarded", expected.getMessage());
        }
    }
}
