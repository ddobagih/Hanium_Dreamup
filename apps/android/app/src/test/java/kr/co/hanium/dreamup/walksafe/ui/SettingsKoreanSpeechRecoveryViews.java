package kr.co.hanium.dreamup.walksafe.ui;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Controlled non-final Android View setters; real policy/presenter methods are not replaced.
 * TextView.setText(CharSequence) is final in android.jar: host defaults cannot observe its text.
 * Cause text binding is checked structurally; the Android UI case owns actual displayed text.
 */
public final class SettingsKoreanSpeechRecoveryViews {
    public static final class FakeText extends TextView {
        private int visible=View.GONE;
        public FakeText(){super((Context)null);}

        private CharSequence observedDescription;
        @Override public void setContentDescription(CharSequence value){observedDescription=value;}
        @Override public CharSequence getContentDescription(){return observedDescription;}
        @Override public void setTextSize(float value){}
        @Override public void setTypeface(android.graphics.Typeface value){}
        @Override public void setTextColor(int value){}
        @Override public void setEnabled(boolean value){}

        @Override public void setVisibility(int v){visible=v;}
        @Override public int getVisibility(){return visible;}
    }
    public static final class FakeButton extends Button {
        private int visible=View.GONE;
        public FakeButton(){super((Context)null);}

        private CharSequence observedDescription;
        @Override public void setContentDescription(CharSequence value){observedDescription=value;}
        @Override public CharSequence getContentDescription(){return observedDescription;}
        @Override public void setTextSize(float value){}
        @Override public void setTypeface(android.graphics.Typeface value){}
        @Override public void setTextColor(int value){}
        @Override public void setEnabled(boolean value){}

        @Override public void setVisibility(int v){visible=v;}
        @Override public int getVisibility(){return visible;}
    }
    public static final class FakeLayout extends LinearLayout {
        private int visible=View.GONE;
        public FakeLayout(){super((Context)null);}
        @Override public void setVisibility(int v){visible=v;}
        @Override public int getVisibility(){return visible;}
    }
    public static final class RestrictedContext extends ContextWrapper {
        public RestrictedContext(){super(null);}
        @Override public boolean isRestricted(){return true;}
    }
    static final Object allocator;
    static final Method allocate;
    static {
        try {
            Class<?> type=Class.forName("sun.misc.Unsafe"); Field f=type.getDeclaredField("theUnsafe");f.setAccessible(true);allocator=f.get(null);
            allocate=type.getMethod("allocateInstance",Class.class);
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    static <T>T panel(Class<T> type)throws Exception {
        T value=type.cast(allocate.invoke(allocator,type));
        for(Field f:type.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers()))continue;
            Object field=null;
            if(f.getType()==Button.class)field=new FakeButton();
            else if(f.getType()==TextView.class)field=new FakeText();
            else if(f.getType()==LinearLayout.class)field=new FakeLayout();
            else if(f.getType()==View.class)field=new FakeText();
            else if(Map.class.isAssignableFrom(f.getType()))field=new LinkedHashMap<>();
            if(field!=null){f.setAccessible(true);f.set(value,field);}
        }
        return value;
    }
    public final OnboardingReadinessPanel onboarding;
    public final NativeScreenPresenter presenter;
    public final SettingsPrivacyPanel settings;
    public SettingsKoreanSpeechRecoveryViews()throws Exception {
        onboarding=panel(OnboardingReadinessPanel.class);
        presenter=new NativeScreenPresenter(new WalkSafeViewStyle(new RestrictedContext()));
        settings=panel(SettingsPrivacyPanel.class);
        presenter.bindPanels(new NativeScreenPresenter.Panels(panel(AccountAccessPanel.class),onboarding,
            settings,panel(DestinationGuidancePanel.class),panel(HomePanel.class)));
        presenter.bindRuntimeViews(new NativeScreenPresenter.RuntimeViews(new FakeText(),new FakeText(),new FakeText(),null));
    }
}
