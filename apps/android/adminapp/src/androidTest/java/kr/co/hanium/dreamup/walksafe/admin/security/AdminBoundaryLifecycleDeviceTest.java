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
            List<EditText> sensitive = new ArrayList<>();
            scenario.onActivity(activity -> {
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                collect(activity.getWindow().getDecorView(), sensitive);
                assertTrue("password/TOTP fields must actually be present", sensitive.size() >= 2);
                for (EditText field : sensitive) field.setText("synthetic-test-input");
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.onActivity(activity -> {
                for (EditText field : sensitive) assertEquals("background must wipe: " + field.getHint(), "", field.getText().toString());
            });
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> {
                for (EditText field : sensitive) assertEquals("return must not restore: " + field.getHint(), "", field.getText().toString());
            });
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
