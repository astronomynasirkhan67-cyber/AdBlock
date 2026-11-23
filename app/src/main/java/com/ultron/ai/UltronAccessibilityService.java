package com.ultron.ai;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

/**
 * Lets ULTRON act inside other apps when asked by voice: back / home / recents, notifications,
 * screenshot, scroll, press a button by its name, and type text into the focused box.
 * It also lets the floating core stay on top of other apps without the "display over other apps" permission.
 */
public class UltronAccessibilityService extends AccessibilityService {
    public static volatile UltronAccessibilityService inst;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(Intent intent) {
        inst = null;
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        inst = null;
        super.onDestroy();
    }

    // ---------------------------------------------------------------- global actions

    public boolean back() { return performGlobalAction(GLOBAL_ACTION_BACK); }
    public boolean home() { return performGlobalAction(GLOBAL_ACTION_HOME); }
    public boolean recents() { return performGlobalAction(GLOBAL_ACTION_RECENTS); }
    public boolean notifications() { return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS); }
    public boolean quickSettings() { return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS); }

    public boolean screenshot() {
        return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
    }

    public boolean lockScreen() {
        return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    // ---------------------------------------------------------------- screen actions

    public boolean scroll(boolean down) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isScrollable()) {
                return n.performAction(down ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return false;
    }

    /** Presses the button / item whose text or description matches the spoken name. */
    public boolean click(String label) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        String want = label.toLowerCase(Locale.ROOT).trim();
        List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(label);
        AccessibilityNodeInfo best = null;
        for (AccessibilityNodeInfo n : hits) {
            CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
            if (t != null && t.toString().toLowerCase(Locale.ROOT).trim().equals(want)) { best = n; break; }
            if (best == null) best = n;
        }
        if (best == null) {
            // fall back to content descriptions (icons)
            best = findByDescription(root, want);
        }
        if (best == null) return false;
        AccessibilityNodeInfo n = best;
        while (n != null && !n.isClickable()) n = n.getParent();
        return n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private AccessibilityNodeInfo findByDescription(AccessibilityNodeInfo root, String want) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            CharSequence d = n.getContentDescription();
            if (d != null && d.toString().toLowerCase(Locale.ROOT).contains(want)) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }

    /** Types the text into the focused text box (adds to what is already there). */
    public boolean typeNow(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo n = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (n == null || !n.isEditable()) n = firstEditable(root);
        if (n == null) return false;
        CharSequence cur = n.getText();
        String base = cur == null ? "" : cur.toString();
        if (Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) base = "";
        String joined = base.isEmpty() ? text : base + (base.endsWith(" ") || base.endsWith("\n") ? "" : " ") + text;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, joined);
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private AccessibilityNodeInfo firstEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n.isEditable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return null;
    }
}
