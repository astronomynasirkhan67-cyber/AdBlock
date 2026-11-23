package com.ultron.ai.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** Jarvis / Ultron style "virtual presence": glowing core, rotating rings and a reactive pulse ring. */
public class OrbView extends View {
    public enum State { OFFLINE, IDLE, LISTENING, THINKING, SPEAKING }

    private static final int RED = Color.rgb(232, 40, 58);
    private static final int HOT = Color.rgb(255, 150, 140);

    private State state = State.OFFLINE;
    private volatile float micLevel = 0f;   // 0..1 from the microphone
    private float level = 0f;               // smoothed energy
    private float time = 0f;
    private long lastNanos = 0;

    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float[] tickPts = new float[72 * 4];

    public OrbView(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        glow.setShader(new RadialGradient(0, 0, 1f,
                new int[]{Color.argb(255, 255, 90, 100), Color.argb(150, 232, 40, 58), Color.argb(0, 232, 40, 58)},
                new float[]{0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        core.setStyle(Paint.Style.FILL);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.BUTT);
        tick.setStyle(Paint.Style.STROKE);
        tick.setStrokeCap(Paint.Cap.ROUND);
        ripple.setStyle(Paint.Style.STROKE);
    }

    public void setState(State s) { state = s; }
    public State getState() { return state; }
    public void setMicLevel(float l) { micLevel = Math.max(0f, Math.min(1f, l)); }

    @Override protected void onDraw(Canvas canvas) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0.016f : Math.min(0.05f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        time += dt;

        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float R = Math.min(w, h) * 0.26f;

        // target energy per state
        float target;
        float spin;
        float alpha = 1f;
        switch (state) {
            case LISTENING:
                target = 0.15f + micLevel * 0.85f; spin = 0.6f; break;
            case THINKING:
                target = 0.30f + 0.10f * (float) Math.sin(time * 6f); spin = 3.2f; break;
            case SPEAKING:
                target = 0.40f + 0.30f * (float) Math.abs(Math.sin(time * 9.0f))
                        + 0.18f * (float) Math.sin(time * 17.3f + 1.3f); spin = 1.1f; break;
            case IDLE:
                target = 0.08f + 0.05f * (float) Math.sin(time * 1.6f); spin = 0.25f; break;
            default:
                target = 0.03f; spin = 0.1f; alpha = 0.45f; break;
        }
        target = Math.max(0f, Math.min(1f, target));
        level += (target - level) * Math.min(1f, dt * 10f);

        // soft glow
        float gr = R * (1.55f + 0.55f * level);
        glow.setAlpha((int) (255 * alpha * (0.55f + 0.45f * level)));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.scale(gr, gr);
        canvas.drawCircle(0, 0, 1f, glow);
        canvas.restore();

        // segmented rings (rotating at different speeds / directions)
        drawRing(canvas, cx, cy, R * 0.62f, R * 0.045f, 4, 62f, time * 40f * spin, alpha * 0.95f);
        drawRing(canvas, cx, cy, R * 0.82f, R * 0.030f, 6, 38f, -time * 55f * spin, alpha * 0.80f);
        drawRing(canvas, cx, cy, R * 1.02f, R * 0.060f, 3, 78f, time * 22f * spin, alpha);

        // radial "voice" ring
        int n = 72;
        float base = R * 1.16f;
        tick.setColor(RED);
        tick.setStrokeWidth(Math.max(2f, R * 0.022f));
        for (int i = 0; i < n; i++) {
            double a = Math.toRadians(i * (360.0 / n)) + time * 0.15f * spin;
            float wave = 0.5f + 0.5f * (float) Math.sin(i * 0.55f + time * (state == State.THINKING ? 9f : 5f));
            float wave2 = 0.5f + 0.5f * (float) Math.sin(i * 1.7f - time * 7.3f);
            float len = R * 0.04f + R * 0.34f * level * (0.35f + 0.65f * wave * wave2);
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            tickPts[i * 4] = cx + base * ca;
            tickPts[i * 4 + 1] = cy + base * sa;
            tickPts[i * 4 + 2] = cx + (base + len) * ca;
            tickPts[i * 4 + 3] = cy + (base + len) * sa;
        }
        tick.setAlpha((int) (235 * alpha));
        canvas.drawLines(tickPts, tick);

        // expanding ripples while listening / speaking
        if (state == State.LISTENING || state == State.SPEAKING) {
            ripple.setColor(RED);
            for (int k = 0; k < 3; k++) {
                float f = (time * 0.55f + k / 3f) % 1f;
                float r = R * (1.55f + 0.9f * f);
                ripple.setStrokeWidth(R * 0.012f);
                ripple.setAlpha((int) (140 * (1f - f) * (0.3f + level)));
                canvas.drawCircle(cx, cy, r, ripple);
            }
        }

        // hot core
        float cr = R * (0.30f + 0.14f * level);
        core.setColor(Color.argb((int) (255 * alpha), 255, 70, 80));
        canvas.drawCircle(cx, cy, cr * 1.25f, core);
        core.setColor(Color.argb((int) (255 * alpha), 255, 205, 200));
        canvas.drawCircle(cx, cy, cr * 0.78f, core);
        core.setColor(Color.argb((int) (255 * alpha), 255, 255, 255));
        canvas.drawCircle(cx, cy, cr * 0.38f, core);

        postInvalidateOnAnimation();
    }

    private void drawRing(Canvas c, float cx, float cy, float r, float stroke,
                          int segs, float sweep, float rot, float a) {
        arc.setColor(RED);
        arc.setStrokeWidth(stroke);
        arc.setAlpha((int) (255 * a));
        oval.set(cx - r, cy - r, cx + r, cy + r);
        float step = 360f / segs;
        for (int i = 0; i < segs; i++) {
            c.drawArc(oval, rot + i * step, sweep, false, arc);
        }
    }
}
