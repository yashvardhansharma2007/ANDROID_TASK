package com.tasklite.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Optional helper. After the user taps "End task", MainActivity opens the app-info screen and calls
 * request(). This service then presses "Force stop" and the confirmation "OK" in the Settings app,
 * and goes back to TaskLite. It acts only for 15 seconds after request() and only inside
 * com.android.settings (see res/xml/accessibility_config.xml); it reads nothing else.
 */
public final class ForceStopService extends AccessibilityService {
    private static volatile String pendingPkg;
    private static volatile long pendingAt;
    private static boolean clicked;

    private final Handler h = new Handler(Looper.getMainLooper());

    static void request(String pkg) {
        pendingPkg = pkg;
        pendingAt = System.currentTimeMillis();
        clicked = false;
    }

    static boolean isEnabled(Context c) {
        String s = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(c.getPackageName() + "/") && s.contains("ForceStopService");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (pendingPkg == null) return;
        if (System.currentTimeMillis() - pendingAt > 15000) {
            pendingPkg = null;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (clicked) { // the "Force stop?" dialog is open: press OK
            if (click(root.findAccessibilityNodeInfosByViewId("android:id/button1")) == 1
                    || click(root.findAccessibilityNodeInfosByText("OK")) == 1) {
                done();
            }
            return;
        }
        int r = click(root.findAccessibilityNodeInfosByText("force stop"));
        if (r == 0) r = click(root.findAccessibilityNodeInfosByText("force-stop"));
        if (r == 1) clicked = true;
        else if (r == 2) done(); // button is greyed out: the app is not running
    }

    /** 1 = clicked, 2 = button found but disabled, 0 = nothing found. */
    private static int click(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return 0;
        for (AccessibilityNodeInfo n : nodes) {
            AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c == null) continue;
            if (!c.isEnabled()) return 2;
            if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return 1;
        }
        return 0;
    }

    private void done() {
        pendingPkg = null;
        clicked = false;
        h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 600); // back to TaskLite
    }

    @Override
    public void onInterrupt() {
    }
}
