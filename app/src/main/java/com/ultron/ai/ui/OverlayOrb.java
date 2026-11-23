package com.ultron.ai.ui;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.ultron.ai.UltronAccessibilityService;

/** The small floating Ultron core that stays on top of other apps. Drag it; tap it to talk / pause. */
public class OverlayOrb {
    private final Context ctx;
    private WindowManager wm;
    private final Runnable onTap;
    private OrbView orb;
    private WindowManager.LayoutParams lp;
    private boolean shown = false;
    private int lastX = 24, lastY = 320;

    public OverlayOrb(Context c, Runnable onTap) {
        this.ctx = c.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.onTap = onTap;
    }

    public static boolean canShow(Context c) {
        return UltronAccessibilityService.inst != null || Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    public boolean isShown() { return shown; }

    public void show() {
        if (shown || !canShow(ctx)) return;
        try {
            UltronAccessibilityService svc = UltronAccessibilityService.inst;
            Context uctx = svc != null ? svc : ctx;
            wm = (WindowManager) uctx.getSystemService(Context.WINDOW_SERVICE);
            orb = new OrbView(uctx);
            int size = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 120, ctx.getResources().getDisplayMetrics());
            lp = new WindowManager.LayoutParams(size, size,
                    svc != null ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = lastX;
            lp.y = lastY;
            orb.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY;
                int startX, startY;
                boolean moved;
                @Override public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY();
                            startX = lp.x; startY = lp.y; moved = false;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                            if (Math.abs(dx) > 12 || Math.abs(dy) > 12) moved = true;
                            if (moved) {
                                lp.x = startX + (int) dx;
                                lp.y = startY + (int) dy;
                                lastX = lp.x; lastY = lp.y;
                                wm.updateViewLayout(orb, lp);
                            }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!moved && onTap != null) onTap.run();
                            return true;
                        default:
                            return false;
                    }
                }
            });
            wm.addView(orb, lp);
            shown = true;
        } catch (Exception e) {
            shown = false;
        }
    }

    public void hide() {
        if (!shown) return;
        try { wm.removeView(orb); } catch (Exception ignored) { }
        shown = false;
    }

    public void setState(OrbView.State s) { if (orb != null) orb.setState(s); }
    public void setMicLevel(float l) { if (orb != null) orb.setMicLevel(l); }
}
