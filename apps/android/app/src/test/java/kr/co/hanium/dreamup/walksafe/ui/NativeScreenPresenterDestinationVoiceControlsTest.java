package kr.co.hanium.dreamup.walksafe.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual presenter execution with controlled View setters; no Activity/window or physical UI. */
public final class NativeScreenPresenterDestinationVoiceControlsTest {
    private static final Object ALLOCATOR;
    private static final Method ALLOCATE_INSTANCE;
    static {
        try {
            Class<?> allocatorClass = Class.forName("sun.misc.Unsafe");
            Field field = allocatorClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            ALLOCATOR = field.get(null);
            ALLOCATE_INSTANCE = allocatorClass.getMethod("allocateInstance", Class.class);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }
    static final class Layout extends LinearLayout {
        private int observedVisibility = View.GONE;
        final List<View> children = new ArrayList<>();
        Layout() { super((Context) null); }
        @Override public void setVisibility(int value) { observedVisibility = value; }
        @Override public int getVisibility() { return observedVisibility; }
        @Override public void addView(View child) { children.add(child); }
    }
    static final class Status extends TextView {
        private int observedVisibility = View.GONE;
        Status() { super((Context) null); }
        @Override public void setVisibility(int value) { observedVisibility = value; }
        @Override public int getVisibility() { return observedVisibility; }
    }
    private static <T> T unconstructedPanel(Class<T> type) throws ReflectiveOperationException {
        return type.cast(ALLOCATE_INSTANCE.invoke(ALLOCATOR, type));
    }
    private static void bind(Object instance, String name, Object value) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }
    private static final class Fixture {
        final Layout runtime = new Layout();
        final Layout search = new Layout();
        final Layout confirmation = new Layout();
        final Layout guidance = new Layout();
        final Layout voice = new Layout();
        final DestinationGuidancePanel destination = unconstructedPanel(DestinationGuidancePanel.class);
        final NativeScreenPresenter presenter = new NativeScreenPresenter(unconstructedPanel(WalkSafeViewStyle.class));
        Fixture() throws ReflectiveOperationException {
            bind(destination, "runtimeControls", runtime);
            bind(destination, "nativeDestinationControls", search);
            bind(destination, "nativeDestinationConfirmationControls", confirmation);
            bind(destination, "nativeGuidanceControls", guidance);
            bind(destination, "nativeVoiceControls", voice);
            bind(destination, "gatewayVoiceStatusText", new Status());
            HomePanel home = unconstructedPanel(HomePanel.class);
            bind(home, "homeCardGrid", new Layout());
            runtime.addView(search); runtime.addView(confirmation); runtime.addView(guidance); runtime.addView(voice);
            presenter.bindPanels(new NativeScreenPresenter.Panels(
                unconstructedPanel(AccountAccessPanel.class), unconstructedPanel(OnboardingReadinessPanel.class),
                unconstructedPanel(SettingsPrivacyPanel.class), destination, home));
            presenter.publishVoiceTargets(destination);
            presenter.publishSearchTargets(destination);
        }
        void render(String page, boolean featureVisible) {
            // No render predicate is reimplemented here: the actual owner decides all visibility.
            presenter.beginFrame(new NativeScreenPresenter.FrameFacts(page, true, false, false, featureVisible, false, false));
        }
    }
    @Test public void destinationCandidatesHideVoiceControlsWithoutChangingSearchBinding() throws Exception {
        Fixture fixture = new Fixture();
        List<View> originalChildren = List.copyOf(fixture.runtime.children);
        fixture.render("DESTINATION_SEARCH", true);
        assertEquals(View.VISIBLE, fixture.runtime.getVisibility());
        assertEquals(View.VISIBLE, fixture.search.getVisibility());
        assertEquals(View.GONE, fixture.voice.getVisibility());
        assertEquals(View.GONE, fixture.guidance.getVisibility());
        assertEquals(View.GONE, fixture.confirmation.getVisibility());
        assertSame(fixture.destination, fixture.presenter.voiceBinding());
        assertSame(fixture.destination, fixture.presenter.searchBinding());
        assertSame(fixture.voice, fixture.presenter.voiceBinding().getNativeVoiceControls());
        assertEquals(originalChildren, fixture.runtime.children);
    }
    @Test public void onlyVoiceCommandPageShowsVoiceControls() throws Exception {
        Fixture fixture = new Fixture();
        fixture.render("VOICE_COMMAND", true);
        assertEquals(View.VISIBLE, fixture.voice.getVisibility());
        assertEquals(View.GONE, fixture.search.getVisibility());
        fixture.render("DESTINATION_SEARCH", true);
        assertEquals(View.GONE, fixture.voice.getVisibility());
        for (NativeUiPage page : NativeUiPage.values()) {
            if (page == NativeUiPage.VOICE_COMMAND) continue;
            fixture.render(page.name(), true);
            assertEquals(page.name(), View.GONE, fixture.voice.getVisibility());
        }
    }
    @Test public void unavailableFeaturesNeverRevealVoiceOrSearchControlsEvenAfterAVisibleFrame() throws Exception {
        Fixture fixture = new Fixture();
        fixture.render("VOICE_COMMAND", true);
        assertEquals(View.VISIBLE, fixture.voice.getVisibility());
        for (NativeUiPage page : NativeUiPage.values()) {
            fixture.render(page.name(), false);
            assertEquals(page.name(), View.GONE, fixture.runtime.getVisibility());
            assertEquals(page.name(), View.GONE, fixture.search.getVisibility());
            assertEquals(page.name(), View.GONE, fixture.voice.getVisibility());
        }
    }
}
