package app.longpress;

import android.app.Activity;
import android.app.Application;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Long-press-to-2x for the X Android app.
 * Entry points injected by the patch:
 *   init(Application)       - from Application.onCreate
 *   registerPlayer(Object)  - from the end of the Media3 ExoPlayerImpl constructor
 * Player method names are obfuscated; they are passed in by the patch via PLAYER_* constants
 * (rewritten at patch time) and resolved reflectively.
 */
public final class LongPressSpeed {
    private static final String TAG = "LongPressSpeed";
    public static String SET_PARAMS = "setPlaybackParameters"; // patched
    public static String GET_PARAMS = "getPlaybackParameters"; // patched
    public static String IS_PLAYING = "isPlaying";             // patched
    public static String PARAMS_CLASS = "androidx.media3.common.PlaybackParameters"; // patched
    static final long LONG_PRESS_MS = 250;
    static final float FAST = 2f;

    private static final List<WeakReference<Object>> players = new ArrayList<>();
    private static boolean inited;

    public static void registerPlayer(Object player) {
        synchronized (players) {
            for (Iterator<WeakReference<Object>> it = players.iterator(); it.hasNext(); )
                if (it.next().get() == null) it.remove();
            players.add(new WeakReference<>(player));
        }
    }

    public static void init(Application app) {
        if (inited) return;
        inited = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) { wrap(a); }
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            public void onActivityDestroyed(Activity a) {}
        });
    }

    static void wrap(Activity a) {
        try {
            Window w = a.getWindow();
            if (w == null) return;
            Window.Callback cb = w.getCallback();
            if (cb == null || cb instanceof Wrapper) return;
            w.setCallback(new Wrapper(cb, a));
        } catch (Throwable t) { Log.w(TAG, "wrap", t); }
    }

    // ---------- player control ----------
    static Method mSet, mGet, mPlaying, mWithSpeed;
    static java.lang.reflect.Constructor<?> cParams;

    static Method find(Class<?> c, String name, int argc) {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Method m : k.getDeclaredMethods())
                if (m.getName().equals(name) && m.getParameterTypes().length == argc
                        && (argc == 0 || m.getParameterTypes()[0].getName().equals(PARAMS_CLASS))) {
                    m.setAccessible(true); return m;
                }
        return null;
    }

    static boolean isPlaying(Object p) {
        try {
            if (mPlaying == null) mPlaying = find(p.getClass(), IS_PLAYING, 0);
            Object r = mPlaying.invoke(p);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) { return false; }
    }

    static void setSpeed(Object p, float speed) {
        try {
            if (mSet == null) mSet = find(p.getClass(), SET_PARAMS, 1);
            if (cParams == null) {
                Class<?> pc = Class.forName(PARAMS_CLASS, false, p.getClass().getClassLoader());
                cParams = pc.getDeclaredConstructor(float.class, float.class);
                cParams.setAccessible(true);
            }
            mSet.invoke(p, cParams.newInstance(speed, 1f));
        } catch (Throwable t) { Log.w(TAG, "setSpeed", t); }
    }

    static List<Object> fastPlayers = new ArrayList<>();

    static boolean speedUp() {
        fastPlayers.clear();
        synchronized (players) {
            for (WeakReference<Object> r : players) {
                Object p = r.get();
                if (p != null && isPlaying(p)) fastPlayers.add(p);
            }
        }
        for (Object p : fastPlayers) setSpeed(p, FAST);
        return !fastPlayers.isEmpty();
    }

    static void restore() {
        for (Object p : fastPlayers) setSpeed(p, 1f);
        fastPlayers.clear();
    }

    // ---------- hit test: is the finger on a video surface? ----------
    static boolean onVideo(View v, int x, int y) {
        if (v.getVisibility() != View.VISIBLE) return false;
        Rect r = new Rect();
        if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return false;
        if (v instanceof TextureView || v instanceof SurfaceView) return true;
        String n = v.getClass().getName();
        if (n.contains("PlayerView") || n.contains("VideoView")) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--)
                if (onVideo(g.getChildAt(i), x, y)) return true;
        }
        return false;
    }

    // ---------- window callback wrapper ----------
    static final class Wrapper implements Window.Callback {
        final Window.Callback d;
        final WeakReference<Activity> act;
        final Handler h = new Handler(Looper.getMainLooper());
        float sx, sy; boolean armed, fired; int slop;
        TextView hint;

        Wrapper(Window.Callback d, Activity a) {
            this.d = d; act = new WeakReference<>(a);
            slop = ViewConfiguration.get(a).getScaledTouchSlop();
        }

        final Runnable fire = new Runnable() { public void run() {
            if (!armed) return;
            if (speedUp()) { fired = true; showHint(true); }
        }};

        void showHint(boolean show) {
            Activity a = act.get(); if (a == null) return;
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            float dp = a.getResources().getDisplayMetrics().density;
            if (hint == null) {
                hint = new TextView(a);
                hint.setText("2x \u25B6\u25B6");
                hint.setTextColor(Color.WHITE);
                hint.setTextSize(12);
                hint.setPadding((int) (10 * dp), (int) (3 * dp), (int) (10 * dp), (int) (3 * dp));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0x80000000); bg.setCornerRadius(12 * dp);
                hint.setBackground(bg);
                hint.setClickable(false); hint.setFocusable(false);
            }
            if (show) {
                if (hint.getParent() == null) {
                    int top = 0;
                    WindowInsets wi = decor.getRootWindowInsets();
                    if (wi != null) top = wi.getSystemWindowInsetTop();
                    if (top == 0) {
                        int id = a.getResources().getIdentifier("status_bar_height", "dimen", "android");
                        if (id > 0) top = a.getResources().getDimensionPixelSize(id);
                    }
                    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                    lp.topMargin = top + (int) (56 * dp);
                    decor.addView(hint, lp);
                }
            } else if (hint.getParent() != null) {
                ((ViewGroup) hint.getParent()).removeView(hint);
            }
        }

        void reset() {
            armed = false; h.removeCallbacks(fire);
            if (fired) { restore(); showHint(false); }
            fired = false;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            try {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        reset();
                        sx = e.getRawX(); sy = e.getRawY();
                        Activity a = act.get();
                        if (a != null && onVideo(a.getWindow().getDecorView(), (int) sx, (int) sy)) {
                            armed = true; h.postDelayed(fire, LONG_PRESS_MS);
                        }
                        break;
                    }
                    case MotionEvent.ACTION_MOVE:
                        if (armed && !fired && (Math.abs(e.getRawX() - sx) > slop || Math.abs(e.getRawY() - sy) > slop)) {
                            armed = false; h.removeCallbacks(fire);
                        }
                        break;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (!fired) { armed = false; h.removeCallbacks(fire); }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (fired) {
                            reset();
                            // swallow the tap: deliver CANCEL so the app does not treat it as a click
                            MotionEvent c = MotionEvent.obtain(e);
                            c.setAction(MotionEvent.ACTION_CANCEL);
                            boolean r = d.dispatchTouchEvent(c);
                            c.recycle();
                            return r;
                        }
                        reset();
                        break;
                }
                if (fired) {
                    // while sped up, keep the app from turning this into a scroll/drag/long-press menu
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) return true;
                }
            } catch (Throwable t) { Log.w(TAG, "touch", t); }
            return d.dispatchTouchEvent(e);
        }

        @Override public void onWindowFocusChanged(boolean f) { if (!f) reset(); d.onWindowFocusChanged(f); }

        // ---- plain delegation ----
        public boolean dispatchKeyEvent(KeyEvent e) { return d.dispatchKeyEvent(e); }
        public boolean dispatchKeyShortcutEvent(KeyEvent e) { return d.dispatchKeyShortcutEvent(e); }
        public boolean dispatchTrackballEvent(MotionEvent e) { return d.dispatchTrackballEvent(e); }
        public boolean dispatchGenericMotionEvent(MotionEvent e) { return d.dispatchGenericMotionEvent(e); }
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return d.dispatchPopulateAccessibilityEvent(e); }
        public View onCreatePanelView(int f) { return d.onCreatePanelView(f); }
        public boolean onCreatePanelMenu(int f, Menu m) { return d.onCreatePanelMenu(f, m); }
        public boolean onPreparePanel(int f, View v, Menu m) { return d.onPreparePanel(f, v, m); }
        public boolean onMenuOpened(int f, Menu m) { return d.onMenuOpened(f, m); }
        public boolean onMenuItemSelected(int f, MenuItem i) { return d.onMenuItemSelected(f, i); }
        public void onWindowAttributesChanged(WindowManager.LayoutParams p) { d.onWindowAttributesChanged(p); }
        public void onContentChanged() { d.onContentChanged(); }
        public void onAttachedToWindow() { d.onAttachedToWindow(); }
        public void onDetachedFromWindow() { d.onDetachedFromWindow(); }
        public void onPanelClosed(int f, Menu m) { d.onPanelClosed(f, m); }
        public boolean onSearchRequested() { return d.onSearchRequested(); }
        public boolean onSearchRequested(android.view.SearchEvent e) { return d.onSearchRequested(e); }
        public ActionMode onWindowStartingActionMode(ActionMode.Callback c) { return d.onWindowStartingActionMode(c); }
        public ActionMode onWindowStartingActionMode(ActionMode.Callback c, int t) { return d.onWindowStartingActionMode(c, t); }
        public void onActionModeStarted(ActionMode m) { d.onActionModeStarted(m); }
        public void onActionModeFinished(ActionMode m) { d.onActionModeFinished(m); }
        public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int id) { d.onProvideKeyboardShortcuts(data, menu, id); }
        public void onPointerCaptureChanged(boolean c) { d.onPointerCaptureChanged(c); }
    }
}
