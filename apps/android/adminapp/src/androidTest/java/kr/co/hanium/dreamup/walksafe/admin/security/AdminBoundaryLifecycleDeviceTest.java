package kr.co.hanium.dreamup.walksafe.admin.security;

import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import kr.co.hanium.dreamup.walksafe.admin.AdminBoundaryActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class AdminBoundaryLifecycleDeviceTest {
    @Test public void backgroundClearsCredentialsAndWindowBlocksCapture() {
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitWindowFocus(scenario);
            List<EditText> sensitive = new ArrayList<>();
            scenario.onActivity(activity -> {
                assertTrue("credential entry requires the visible, started screen", activityStarted(activity));
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                collect(activity.getWindow().getDecorView(), sensitive);
                assertTrue("password/TOTP fields must actually be present", sensitive.size() >= 2);
                for (EditText field : sensitive) field.setText("synthetic-test-input");
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> {
                assertFalse("onStop must complete before inspecting credentials", activityStarted(activity));
                for (EditText field : sensitive) assertEquals("background must wipe: " + field.getHint(), "", field.getText().toString());
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                for (EditText field : sensitive) assertEquals("return must not restore: " + field.getHint(), "", field.getText().toString());
            });
        }
    }
    @Test public void recreationDoesNotRestoreSecretInputsAndKeepsCaptureProtection() {
        try (ActivityScenario<AdminBoundaryActivity> scenario = ActivityScenario.launch(AdminBoundaryActivity.class)) {
            awaitWindowFocus(scenario);
            List<EditText> oldFields = new ArrayList<>();
            final AdminBoundaryActivity[] oldActivity = new AdminBoundaryActivity[1];
            scenario.onActivity(activity -> {
                oldActivity[0] = activity;
                assertTrue(activity.hasWindowFocus());
                collect(activity.getWindow().getDecorView(), oldFields);
                assertTrue(oldFields.size() >= 2);
                for (EditText field : oldFields) {
                    assertFalse("secrets must not participate in OS view-state restoration", field.isSaveEnabled());
                    field.setText("synthetic-recreation-secret");
                    assertEquals("synthetic-recreation-secret", field.getText().toString());
                }
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertNotSame(oldActivity[0], activity);
                assertTrue(oldActivity[0].isDestroyed());
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                List<EditText> freshFields = new ArrayList<>();
                collect(activity.getWindow().getDecorView(), freshFields);
                assertEquals(oldFields.size(), freshFields.size());
                for (EditText field : oldFields) assertEquals("old views must be wiped", "", field.getText().toString());
                for (EditText field : freshFields) {
                    assertEquals("new views must not restore secrets", "", field.getText().toString());
                    assertFalse(field.isSaveEnabled());
                }
            });
        }
    }

    private static void awaitWindowFocus(ActivityScenario<AdminBoundaryActivity> scenario) {
        long until = android.os.SystemClock.elapsedRealtime() + 5000L;
        boolean[] focused = {false};
        while (!focused[0] && android.os.SystemClock.elapsedRealtime() < until) {
            scenario.onActivity(activity -> focused[0] = activity.hasWindowFocus());
            if (!focused[0]) android.os.SystemClock.sleep(50L);
        }
        assertTrue("actual window focus is required", focused[0]);
    }

    private static boolean activityStarted(AdminBoundaryActivity activity) {
        try {
            java.lang.reflect.Field field = AdminBoundaryActivity.class.getDeclaredField("activityStarted");
            field.setAccessible(true);
            return field.getBoolean(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void collect(View view, List<EditText> fields) {
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            int type = field.getInputType();
            int variation = type & android.text.InputType.TYPE_MASK_VARIATION;
            int kind = type & android.text.InputType.TYPE_MASK_CLASS;
            if ((kind == android.text.InputType.TYPE_CLASS_TEXT &&
                (variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                 variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (kind == android.text.InputType.TYPE_CLASS_NUMBER &&
                 variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)) fields.add(field);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), fields);
        }
    }
}
