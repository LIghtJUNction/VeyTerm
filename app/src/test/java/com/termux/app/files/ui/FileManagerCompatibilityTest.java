package com.termux.app.files.ui;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import com.termux.R;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class FileManagerCompatibilityTest {
    @Test @Config(sdk = 21) public void oldAndroidStartsWorkspaceWithoutEnablingShizuku() {
        Application app = RuntimeEnvironment.getApplication();
        assertFalse(app.getResources().getBoolean(R.bool.shizuku_available));
        new java.io.File(app.getFilesDir(), "home").mkdirs();
        ActivityController<FileManagerActivity> controller = Robolectric.buildActivity(FileManagerActivity.class).create().start().resume();
        try {
            assertNotNull(controller.get().access());
            assertTrue(controller.get().activeDirectory().isLocal());
        } finally { controller.pause().stop().destroy(); }
    }
    @Test @Config(sdk = 24) public void androidSevenEnablesShizukuProvider() {
        assertTrue(RuntimeEnvironment.getApplication().getResources().getBoolean(R.bool.shizuku_available));
    }
    @Test @Config(sdk = 28, qualifiers = "w800dp-h360dp-land") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void shortLandscapeKeepsBothFileListsVisible() {
        Application app = RuntimeEnvironment.getApplication();
        new java.io.File(app.getFilesDir(), "home").mkdirs();
        ActivityController<FileManagerActivity> controller = Robolectric.buildActivity(FileManagerActivity.class).create().start().resume().visible();
        try {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            View content = controller.get().findViewById(android.R.id.content);
            float density = controller.get().getResources().getDisplayMetrics().density;
            int width = Math.round(800 * density), height = Math.round(312 * density);
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            content.layout(0, 0, width, height);
            for (String name : new String[]{"Left file list", "Right file list"}) {
                View list = described(content, name);
                assertNotNull(name, list);
                assertTrue(name + " must retain a usable viewport: " + list.getHeight() + "px, config="
                    + controller.get().getResources().getConfiguration().screenHeightDp + "dp, density=" + density
                    + ", scaled=" + controller.get().getResources().getDisplayMetrics().scaledDensity
                    + ", font=" + controller.get().getResources().getConfiguration().fontScale
                    + ", content=" + content.getHeight() + "px, pane=" + dimensions((ViewGroup) list.getParent())
                    + ", root=" + dimensions((ViewGroup) list.getParent().getParent().getParent()), list.getHeight() >= Math.round(64 * density));
            }
        } finally { controller.pause().stop().destroy(); }
    }
    private static String dimensions(ViewGroup group) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            text.append(child.getClass().getSimpleName()).append('=').append(child.getHeight()).append('/').append(child.getVisibility()).append(' ');
        }
        return text.toString();
    }
    private static View described(View view, String name) {
        if (name.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = described(group.getChildAt(i), name);
                if (child != null) return child;
            }
        }
        return null;
    }
}
