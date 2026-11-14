package io.padtap;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Foreground service that owns the overlay windows: cursor/markers layer, floating icon, menu panel. */
public class OverlayService extends Service implements Pad.View {
    static final String ACT_STOP = "io.padtap.STOP";
    static final String ACT_MENU = "io.padtap.MENU";
    static boolean running;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, OverlayService.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, OverlayService.class));
    }

    private WindowManager wm;
    private Pad pad;
    private float dp;
    private Context themed;

    private FrameLayout layerRoot;
    private Layer layer;
    private WindowManager.LayoutParams layerLp;
    private MaxScroll hintScroll;
    private LinearLayout hint;

    private TextView handle;
    private WindowManager.LayoutParams handleLp;

    private LinearLayout panel;
    private boolean panelShown;
    private long panelClosedAt;
    private TextView panelStatus, panelProfile;

    private Toast toast;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        dp = getResources().getDisplayMetrics().density;
        themed = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        startForeground(1, buildNotification());
        wm = getSystemService(WindowManager.class);
        pad = Pad.get(this);
        pad.view = this;
        buildLayer();
        buildHandle();
        pad.bind();
        pad.startLoop();
        pad.fireWatchers();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACT_STOP.equals(intent.getAction())) {
            stopSelf();
        } else if (intent != null && ACT_MENU.equals(intent.getAction())) {
            if (pad.setup) pad.setSetup(false);
            showPanel(true);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        pad.stopLoop();
        pad.unbind();
        pad.view = null;
        safeRemove(layerRoot);
        safeRemove(handle);
        if (panelShown) safeRemove(panel);
        pad.fireWatchers();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Point sz = realSize();
        layerLp.width = sz.x;
        layerLp.height = sz.y;
        safeUpdate(layerRoot, layerLp);
        placeHandle();
        if (pad.setup) placeHint();
        if (panelShown) {
            showPanel(false);
            showPanel(true);
        }
    }

    // ---------------- helpers ----------------

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void safeRemove(View v) {
        try {
            if (v != null && v.isAttachedToWindow()) wm.removeView(v);
        } catch (Throwable ignored) {
        }
    }

    private void safeUpdate(View v, WindowManager.LayoutParams lp) {
        try {
            if (v != null && v.isAttachedToWindow()) wm.updateViewLayout(v, lp);
        } catch (Throwable ignored) {
        }
    }

    private WindowManager.LayoutParams lp(int w, int h, int flags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    private Point realSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect b = wm.getCurrentWindowMetrics().getBounds();
            return new Point(b.width(), b.height());
        }
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        return p;
    }

    private GradientDrawable round(int color, float radiusDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * dp);
        if (strokeColor != 0) g.setStroke(px(1), strokeColor);
        return g;
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(themed);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** kind: 0 normal, 1 primary, 2 danger */
    private Button btn(String s, int kind, View.OnClickListener l) {
        Button b = new Button(themed);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(px(12), px(8), px(12), px(8));
        int bg = kind == 1 ? 0xFF3B7CFF : kind == 2 ? 0xFF5A2630 : 0xFF2A2E40;
        int st = kind == 1 ? 0xFF3B7CFF : kind == 2 ? 0xFF83404C : 0xFF555A70;
        b.setBackground(round(bg, 10, st));
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams margins(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(px(l), px(t), px(r), px(b));
        return p;
    }

    // ---------------- notification ----------------

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("run", "PadTap 動作中", NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction(ACT_STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent menu = PendingIntent.getService(this, 2,
                new Intent(this, OverlayService.class).setAction(ACT_MENU), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, 3,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "run")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("PadTap 動作中")
                .setContentText("SELECT＋START でボタン配置の設定")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "メニュー", menu).build())
                .addAction(new Notification.Action.Builder(null, "停止", stop).build())
                .build();
    }

    // ---------------- layer (cursor, markers, setup) ----------------

    private void buildLayer() {
        layerRoot = new FrameLayout(this);
        layer = new Layer(this);
        layerRoot.addView(layer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        hintScroll = new MaxScroll(this);
        hintScroll.setBackground(round(0xF2101420, 14, 0xFF444A66));
        hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setPadding(px(14), px(10), px(14), px(12));
        hintScroll.addView(hint);
        layerRoot.addView(hintScroll, new FrameLayout.LayoutParams(-2, -2));
        hintScroll.setVisibility(View.GONE);

        Point sz = realSize();
        layerLp = lp(sz.x, sz.y, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // Android 12+: touches only pass through overlays at <= 80% opacity.
        layerLp.alpha = 0.8f;
        wm.addView(layerRoot, layerLp);
    }

    /** ScrollView with a height cap. */
    private static final class MaxScroll extends android.widget.ScrollView {
        int maxH = Integer.MAX_VALUE;

        MaxScroll(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
        }
    }

    private void placeHint() {
        Point sz = realSize();
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(Math.min(sz.x - px(20), px(440)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                (pad.cfg.hintBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = px(36);
        hp.bottomMargin = px(24);
        hintScroll.maxH = (int) (sz.y * 0.55f);
        hintScroll.setLayoutParams(hp);
    }

    private static final class Rip {
        final float x, y;
        final long t;

        Rip(float x, float y, long t) {
            this.x = x;
            this.y = y;
            this.t = t;
        }
    }

    static final int NONE = Integer.MIN_VALUE;

    static int stickKey(int s) {
        return -10 - s;
    }

    private static final int COL_L = 0xFF77CCFF, COL_R = 0xFFFFAA55;

    private final class Layer extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final android.graphics.DashPathEffect dash;
        final List<Rip> ripples = new ArrayList<>();
        int hitCode = NONE;
        long hitAt;
        int drag = NONE;
        boolean moved;
        float sx, sy, dx, dy;

        Layer(Context c) {
            super(c);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * dp);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setTextSize(12 * dp);
            label.setFakeBoldText(true);
            label.setShadowLayer(2 * dp, 0, dp, 0xFF000000);
            rp.setStyle(Paint.Style.STROKE);
            rp.setStrokeWidth(2 * dp);
            rp.setColor(0xFF77CCFF);
            dash = new android.graphics.DashPathEffect(new float[]{5 * dp, 4 * dp}, 0);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            pad.setSurface(getWidth(), getHeight(), loc[0], loc[1]);
        }

        private void ring(Canvas c, float x, float y, float r, int color, boolean dashed) {
            stroke.setColor(color);
            stroke.setPathEffect(dashed ? dash : null);
            c.drawCircle(x, y, r, stroke);
            stroke.setPathEffect(null);
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            float S = Math.min(W, H);
            long now = SystemClock.uptimeMillis();
            Engine eng = pad.engine;
            if (pad.setup) c.drawColor(0x77080A12);
            Model.Profile p = pad.cfg.p();
            boolean marks = pad.setup || pad.cfg.showMarks;
            if (marks) {
                // stick zones
                for (int s = 0; s < 2; s++) {
                    Model.Stick k = p.sticks[s];
                    if (k.mode != Model.S_JOY && k.mode != Model.S_SWIPE) continue;
                    float x = k.cx * W, y = k.cy * H;
                    if (drag == stickKey(s) && moved) {
                        x = dx;
                        y = dy;
                    }
                    int col = s == 0 ? COL_L : COL_R;
                    boolean sel = pad.setup && pad.selStick == s;
                    boolean unlimited = k.mode == Model.S_SWIPE && k.unlimited;
                    if (!unlimited) {
                        float R = k.radius * S;
                        fill.setColor((col & 0x00FFFFFF) | (sel ? 0x33000000 : 0x1A000000));
                        c.drawCircle(x, y, R, fill);
                        ring(c, x, y, R, col, k.mode == Model.S_SWIPE);
                    }
                    fill.setColor(sel ? 0xCCFFDD66 : (col & 0x00FFFFFF) | 0x99000000);
                    c.drawCircle(x, y, (unlimited ? 18 : 15) * dp, fill);
                    c.drawText((s == 0 ? "L" : "R") + (unlimited ? "∞" : ""), x, y + label.getTextSize() * 0.35f, label);
                }
                // position buttons
                for (Map.Entry<Integer, Model.Btn> e : p.btns.entrySet()) {
                    Model.Btn b = e.getValue();
                    if (b.target != Model.T_POS) continue;
                    int code = e.getKey();
                    float x = b.x * W, y = b.y * H;
                    if (code == drag && moved) {
                        x = dx;
                        y = dy;
                    }
                    boolean sel = pad.setup && code == pad.sel;
                    boolean hit = code == hitCode && now - hitAt < 100;
                    float r = b.size / 2f * dp * (sel ? 1.1f : hit ? 0.85f : 1f);
                    fill.setColor(sel ? 0x55FFDC64 : hit ? 0x9978C8FF : 0x30FFFFFF);
                    c.drawCircle(x, y, r, fill);
                    ring(c, x, y, r, sel ? 0xFFFFDD66 : 0xFF77CCFF, b.mode == Model.M_TURBO);
                    String lb = Model.label(code) + (b.mode == Model.M_TURBO ? "連" : "");
                    c.drawText(lb, x, y + label.getTextSize() * 0.35f, label);
                }
            }
            for (Iterator<Rip> it = ripples.iterator(); it.hasNext(); ) {
                Rip r = it.next();
                float t = (now - r.t) / 300f;
                if (t >= 1) {
                    it.remove();
                    continue;
                }
                rp.setAlpha((int) (255 * (1 - t)));
                c.drawCircle(r.x, r.y, 18 * dp * (0.4f + 0.9f * t), rp);
            }
            // stick fingers / previews
            for (int s = 0; s < 2; s++) {
                float[] pt = null;
                if (pad.setup && eng.previewOn[s]) pt = eng.preview[s];
                else if (!pad.setup && pad.cfg.showCursor && eng.stickPid[s] >= 0) pt = eng.touch[s];
                if (pt == null) continue;
                fill.setColor((s == 0 ? COL_L : COL_R) & 0xCCFFFFFF);
                c.drawCircle(pt[0], pt[1], 9 * dp, fill);
                ring(c, pt[0], pt[1], 9 * dp, Color.WHITE, false);
            }
            // cursors
            boolean both = p.sticks[0].mode == Model.S_CURSOR && p.sticks[1].mode == Model.S_CURSOR;
            for (int s = 0; s < 2; s++) {
                if (!pad.cursorVisible(s)) continue;
                float x = eng.cur[s][0], y = eng.cur[s][1];
                boolean held = eng.anyHeldOnCursor(s);
                float r = 14 * dp * (held ? 0.8f : 1f);
                int col = s == 0 ? Color.WHITE : COL_R;
                if (held) {
                    fill.setColor(0x8C78C8FF);
                    c.drawCircle(x, y, r, fill);
                }
                stroke.setColor(0x8C000000);
                stroke.setStrokeWidth(5 * dp);
                c.drawCircle(x, y, r, stroke);
                stroke.setStrokeWidth(2 * dp);
                ring(c, x, y, r, col, false);
                fill.setColor(col);
                c.drawCircle(x, y, 2.5f * dp, fill);
                if (both) c.drawText(s == 0 ? "L" : "R", x + r + 7 * dp, y - r, label);
            }
            if (!ripples.isEmpty() || (hitCode != NONE && now - hitAt < 150)) postInvalidateOnAnimation();
        }

        private int findMark(float x, float y) {
            int best = NONE;
            Model.Profile p = pad.cfg.p();
            float bd = Float.MAX_VALUE;
            for (Map.Entry<Integer, Model.Btn> e : p.btns.entrySet()) {
                Model.Btn b = e.getValue();
                if (b.target != Model.T_POS) continue;
                float d = (float) Math.hypot(b.x * getWidth() - x, b.y * getHeight() - y);
                float lim = Math.max(24 * dp, b.size / 2f * dp + 6 * dp);
                if (d < lim && d < bd) {
                    bd = d;
                    best = e.getKey();
                }
            }
            for (int s = 0; s < 2; s++) {
                Model.Stick k = p.sticks[s];
                if (k.mode != Model.S_JOY && k.mode != Model.S_SWIPE) continue;
                float d = (float) Math.hypot(k.cx * getWidth() - x, k.cy * getHeight() - y);
                if (d < 26 * dp && d < bd) {
                    bd = d;
                    best = stickKey(s);
                }
            }
            return best;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!pad.setup) return false;
            float x = e.getX(), y = e.getY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag = findMark(x, y);
                    moved = false;
                    sx = x;
                    sy = y;
                    dx = x;
                    dy = y;
                    if (drag == NONE) {
                        if (pad.sel >= 0) {
                            pad.place(pad.sel, x, y);
                        } else if (pad.selStick >= 0) {
                            int m = pad.cfg.p().sticks[pad.selStick].mode;
                            if (m == Model.S_JOY || m == Model.S_SWIPE) {
                                pad.setStickCenter(pad.selStick, x, y);
                                pad.msg("中心をここにしたよ");
                            } else {
                                pad.msg("仮想スティックかスワイプにすると中心を置けるよ");
                            }
                        } else {
                            pad.msg("先にコントローラーのボタンを押すか、スティックを選んでね");
                        }
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag != NONE) {
                        if (Math.hypot(x - sx, y - sy) > 6 * dp) moved = true;
                        if (moved) {
                            dx = x;
                            dy = y;
                            invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    int d = drag;
                    drag = NONE;
                    if (d == NONE) return true;
                    if (d >= 0) {
                        if (moved) {
                            Model.Btn b = pad.cfg.p().btns.get(d);
                            if (b != null) {
                                b.x = Engine.clamp(x / getWidth(), 0, 1);
                                b.y = Engine.clamp(y / getHeight(), 0, 1);
                            }
                            pad.sel = d;
                            pad.selStick = -1;
                            pad.changed();
                        } else {
                            pad.sel = d;
                            pad.selStick = -1;
                            onSetup();
                        }
                    } else {
                        int s = -10 - d;
                        if (moved) {
                            pad.selStick = s;
                            pad.sel = -1;
                            pad.setStickCenter(s, x, y);
                        } else {
                            pad.selectStick(s);
                        }
                    }
                    moved = false;
                    return true;
                }
                default:
                    return true;
            }
        }
    }

    // ---------------- setup editor (hint panel) ----------------

    private interface Fmt {
        String f(int v);
    }

    private interface Apply {
        void a(int v);
    }

    private interface Pick {
        void p(int i);
    }

    private TextView sectionLabel(String s) {
        TextView t = text(s, 12, 0xFF99AABB, false);
        t.setPadding(0, px(8), 0, px(3));
        return t;
    }

    private View chips(String title, String[] labels, int selected, Pick pick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (title != null) col.addView(sectionLabel(title));
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            row.addView(btn(labels[i], i == selected ? 1 : 0, v -> pick.p(idx)), margins(-2, -2, 0, 0, 6, 0));
        }
        hs.addView(row);
        col.addView(hs);
        return col;
    }

    private View slider(String title, int min, int max, int value, Fmt fmt, Apply apply) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView lab = sectionLabel(title + "：" + fmt.f(value));
        col.addView(lab);
        SeekBar sb = new SeekBar(themed);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                apply.a(v + min);
                lab.setText(title + "：" + fmt.f(v + min));
                layer.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                pad.cfg.save();
            }
        });
        col.addView(sb, new LinearLayout.LayoutParams(-1, -2));
        return col;
    }

    private void hintHeader(String title, String sub) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 15, Color.WHITE, true);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(btn(pad.cfg.hintBottom ? "▲" : "▼", 0, v -> {
            pad.cfg.hintBottom = !pad.cfg.hintBottom;
            pad.cfg.save();
            placeHint();
            updateHint();
        }));
        hint.addView(head);
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(sub, 12, 0xFF99AABB, false);
            s.setPadding(0, px(2), 0, 0);
            hint.addView(s);
        }
    }

    private void footer(boolean back, View.OnClickListener extraAction, String extraLabel, int extraKind) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);
        if (extraAction != null) row.addView(btn(extraLabel, extraKind, extraAction), margins(-2, -2, 0, 0, 6, 0));
        if (back) row.addView(btn("戻る", 0, v -> pad.deselect()), margins(-2, -2, 0, 0, 6, 0));
        row.addView(btn("完了", 1, v -> pad.setSetup(false)));
        hint.addView(row, margins(-1, -2, 0, 12, 0, 0));
    }

    private static String fmt1(float v) {
        return String.format(java.util.Locale.US, "%.1f", v);
    }

    private void updateHint() {
        hint.removeAllViews();
        Model.Profile p = pad.cfg.p();
        if (pad.selStick >= 0) stickEditor(p, pad.selStick);
        else if (pad.sel >= 0) btnEditor(p, pad.sel);
        else idleHint(p);
    }

    private void idleHint(Model.Profile p) {
        hintHeader("設定したいボタンを押してね",
                "コントローラーのボタンを押すと、そのボタンの設定が出るよ\nマークはドラッグで移動／タップで選択\nプロファイル: " + p.name);
        hint.addView(chips("スティック", new String[]{"左スティック", "右スティック"}, -1, pad::selectStick));
        footer(false, v -> {
            pad.setSetup(false);
            showPanel(true);
        }, "メニュー", 0);
    }

    private void btnEditor(Model.Profile p, int code) {
        Model.Btn b = p.btns.get(code);
        String n = Model.label(code);
        String sub;
        if (b == null) sub = "画面をタップすると、その場所を押すボタンになるよ\nもう一度 " + n + " を押すとカーソルの位置に置く";
        else if (b.target == Model.T_POS) sub = "画面をタップ／マークをドラッグで位置を変えられる";
        else sub = (b.target == Model.T_CUR_L ? "左" : "右") + "カーソルの位置を押すボタン";
        hintHeader(n + " の設定", sub);

        hint.addView(chips("押す場所", new String[]{"画面の位置", "左カーソル", "右カーソル"},
                b == null ? -1 : b.target, i -> {
                    Model.Btn bb = p.btns.get(code);
                    if (i == Model.T_POS) {
                        if (bb == null) {
                            pad.msg("画面の押したい場所をタップしてね");
                            return;
                        }
                        bb.target = Model.T_POS;
                    } else {
                        if (bb == null) {
                            bb = new Model.Btn(i);
                            p.btns.put(code, bb);
                        } else {
                            bb.target = i;
                        }
                        int s = i == Model.T_CUR_L ? 0 : 1;
                        if (p.sticks[s].mode != Model.S_CURSOR) {
                            pad.msg(Model.stickName(s) + "を「カーソル」にすると使えるよ");
                        }
                    }
                    pad.changed();
                }));
        if (b != null) {
            hint.addView(chips("押し方", new String[]{"長押し", "連打"}, b.mode, i -> {
                b.mode = i;
                pad.changed();
            }));
            if (b.mode == Model.M_TURBO) {
                hint.addView(slider("連打の間隔", 5, 100, Math.round(b.interval / 10f),
                        v -> (v * 10) + "ms（1秒に約" + fmt1(100f / v) + "回）",
                        v -> b.interval = v * 10));
            }
            if (b.target == Model.T_POS) {
                hint.addView(slider("サイズ", 28, 120, Math.round(b.size), v -> v + "dp", v -> b.size = v));
            }
        }
        footer(true, b == null ? null : v -> pad.clearBtn(code), "解除", 2);
    }

    private void stickEditor(Model.Profile p, int s) {
        Model.Stick k = p.sticks[s];
        String sub;
        switch (k.mode) {
            case Model.S_CURSOR:
                sub = "スティックでカーソルを動かす。押すボタンは各ボタンの設定で「" + (s == 0 ? "左" : "右") + "カーソル」を選んでね";
                break;
            case Model.S_JOY:
                sub = "傾けた分だけ、円の中を指でなぞる。離すと指も離れる\n画面をタップ／Lマークをドラッグで中心を決める";
                break;
            case Model.S_SWIPE:
                sub = "傾けた方向へ指をすべらせる。離すと指を離して中心に戻る\n画面をタップ／マークをドラッグで中心を決める";
                break;
            default:
                sub = "このスティックは使わない";
        }
        if (k.mode == Model.S_JOY || k.mode == Model.S_SWIPE) sub = sub.replace("Lマーク", s == 0 ? "Lマーク" : "Rマーク");
        hintHeader(Model.stickName(s) + " の設定", sub);
        hint.addView(chips("動き", new String[]{"OFF", "カーソル", "仮想スティック", "スワイプ"}, k.mode, i -> {
            k.mode = i;
            pad.changed();
        }));
        if (k.mode != Model.S_OFF) {
            hint.addView(slider("デッドゾーン（反応しない傾き）", 0, 50, Math.round(k.dead * 100), v -> v + "%",
                    v -> k.dead = v / 100f));
        }
        if (k.mode == Model.S_CURSOR || k.mode == Model.S_SWIPE) {
            hint.addView(slider("速さ", 3, 30, Math.round(k.speed * 10), v -> fmt1(v / 10f) + "倍", v -> k.speed = v / 10f));
        }
        if (k.mode == Model.S_SWIPE) {
            hint.addView(chips("動ける範囲", new String[]{"円の中", "無制限"}, k.unlimited ? 1 : 0, i -> {
                k.unlimited = i == 1;
                pad.changed();
            }));
        }
        if (k.mode == Model.S_JOY || (k.mode == Model.S_SWIPE && !k.unlimited)) {
            hint.addView(slider("円の大きさ（半径）", 4, 50, Math.round(k.radius * 100), v -> v + "%",
                    v -> k.radius = v / 100f));
        }
        footer(true, null, null, 0);
    }

    // ---------------- floating icon ----------------

    private void buildHandle() {
        handle = new TextView(this);
        handle.setText("🎮");
        handle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        handle.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x99141620);
        g.setStroke(px(1), 0x59FFFFFF);
        handle.setBackground(g);
        handle.setAlpha(0.85f);
        int s = px(46);
        handleLp = lp(s, s, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        placeHandle();
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            int ox, oy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        ox = handleLp.x;
                        oy = handleLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float ddx = e.getRawX() - sx, ddy = e.getRawY() - sy;
                        if (Math.hypot(ddx, ddy) > 8 * dp) moved = true;
                        if (moved) {
                            handleLp.x = (int) (ox + ddx);
                            handleLp.y = (int) (oy + ddy);
                            clampHandle();
                            safeUpdate(handle, handleLp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            Point sz = realSize();
                            pad.cfg.hx = (handleLp.x + handleLp.width / 2f) / sz.x;
                            pad.cfg.hy = (handleLp.y + handleLp.height / 2f) / sz.y;
                            pad.cfg.save();
                        } else if (SystemClock.uptimeMillis() - panelClosedAt > 300) {
                            showPanel(!panelShown);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        wm.addView(handle, handleLp);
    }

    private void clampHandle() {
        Point sz = realSize();
        handleLp.x = Math.max(0, Math.min(sz.x - handleLp.width, handleLp.x));
        handleLp.y = Math.max(0, Math.min(sz.y - handleLp.height, handleLp.y));
    }

    private void placeHandle() {
        if (handleLp == null) return;
        Point sz = realSize();
        handleLp.x = (int) (pad.cfg.hx * sz.x - handleLp.width / 2f);
        handleLp.y = (int) (pad.cfg.hy * sz.y - handleLp.height / 2f);
        clampHandle();
        safeUpdate(handle, handleLp);
    }

    // ---------------- menu panel ----------------

    private void showPanel(boolean show) {
        if (show == panelShown) {
            if (show) refreshPanel();
            return;
        }
        if (show) {
            buildPanel();
            Point sz = realSize();
            WindowManager.LayoutParams p = lp(Math.min(px(300), sz.x - px(20)), ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
            p.gravity = Gravity.CENTER;
            wm.addView(panel, p);
            panelShown = true;
            refreshPanel();
        } else {
            safeRemove(panel);
            panelShown = false;
            panelClosedAt = SystemClock.uptimeMillis();
        }
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, px(4), 0, px(4));
        return r;
    }

    private CheckBox check(String s, boolean v, android.widget.CompoundButton.OnCheckedChangeListener l) {
        CheckBox cb = new CheckBox(themed);
        cb.setText(s);
        cb.setTextColor(0xFFE6E8F2);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cb.setChecked(v);
        cb.setOnCheckedChangeListener(l);
        return cb;
    }

    private void buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(16), px(14), px(16), px(14));
        panel.setBackground(round(0xF8101420, 16, 0xFF444A66));
        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) showPanel(false);
            return false;
        });

        panel.addView(text("🎮 PadTap", 17, Color.WHITE, true));
        panelStatus = text("", 12, 0xFF99AABB, false);
        panel.addView(panelStatus, margins(-1, -2, 0, 2, 0, 10));

        panel.addView(btn("ボタン配置を設定", 1, v -> {
            showPanel(false);
            pad.setSetup(true);
        }), margins(-1, -2, 0, 0, 0, 8));

        LinearLayout pr = row();
        Button prevB = btn("◀", 0, v -> {
            pad.switchProfile(-1);
            refreshPanel();
        });
        Button nextB = btn("▶", 0, v -> {
            pad.switchProfile(1);
            refreshPanel();
        });
        panelProfile = text("", 15, Color.WHITE, true);
        panelProfile.setGravity(Gravity.CENTER);
        Button addB = btn("＋", 0, v -> {
            pad.cfg.profiles.add(pad.cfg.newProfile());
            pad.cfg.current = pad.cfg.profiles.size() - 1;
            pad.cfg.save();
            pad.releaseAll();
            pad.fireWatchers();
            refreshPanel();
            pad.msg("新しいプロファイルを作ったよ");
        });
        pr.addView(prevB);
        pr.addView(panelProfile, new LinearLayout.LayoutParams(0, -2, 1f));
        pr.addView(nextB);
        pr.addView(addB, margins(-2, -2, 6, 0, 0, 0));
        panel.addView(pr);

        panel.addView(check("カーソル・タップ表示", pad.cfg.showCursor, (b, v) -> {
            pad.cfg.showCursor = v;
            pad.cfg.save();
            layer.invalidate();
        }));
        panel.addView(check("プレイ中も配置を表示", pad.cfg.showMarks, (b, v) -> {
            pad.cfg.showMarks = v;
            pad.cfg.save();
            layer.invalidate();
        }));
        panel.addView(check("コントローラーを独占", pad.cfg.grab, (b, v) -> pad.setGrab(v)));
        TextView gs = text("OFFにするとBで「戻る」が起きたりする", 11, 0xFF8890A8, false);
        panel.addView(gs, margins(-2, -2, 32, 0, 0, 4));

        panel.addView(text("スティックの動きやボタンの連打は「ボタン配置を設定」から", 11, 0xFF8890A8, false),
                margins(-2, -2, 0, 6, 0, 0));

        LinearLayout br = row();
        br.addView(btn("閉じる", 0, v -> showPanel(false)), new LinearLayout.LayoutParams(0, -2, 1f));
        View gap = new View(this);
        br.addView(gap, new LinearLayout.LayoutParams(px(8), 1));
        br.addView(btn("PadTapを停止", 2, v -> {
            showPanel(false);
            stopSelf();
        }), new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(br, margins(-1, -2, 0, 8, 0, 0));
        panel.addView(text("SELECT＋START 同時押しでも配置設定を開けるよ", 11, 0xFF8890A8, false),
                margins(-2, -2, 0, 8, 0, 0));
    }

    private void refreshPanel() {
        if (!panelShown || panel == null) return;
        String st;
        if (pad.svc == null) st = pad.binding ? "サービス起動中…" : "サービス未接続（Shizukuを確認してね）";
        else if (pad.devices.isEmpty()) st = "コントローラー未検出（ボタンを押すか、つなぎ直して）";
        else st = "接続中: " + pad.devices.replace("\n", " / ");
        if (!pad.lastError.isEmpty()) st += "\n" + pad.lastError;
        panelStatus.setText(st);
        Model.Profile p = pad.cfg.p();
        panelProfile.setText(p.name + "  (" + (pad.cfg.current + 1) + "/" + pad.cfg.profiles.size() + ")");
    }

    // ---------------- Pad.View ----------------

    @Override
    public void onPadState() {
        refreshPanel();
        layer.invalidate();
    }

    @Override
    public void onPadFrame() {
        layer.invalidate();
    }

    @Override
    public void onRipple(float x, float y) {
        if (!pad.cfg.showCursor) return;
        layer.ripples.add(new Rip(x, y, SystemClock.uptimeMillis()));
        layer.invalidate();
    }

    @Override
    public void onHit(int code) {
        layer.hitCode = code;
        layer.hitAt = SystemClock.uptimeMillis();
        layer.invalidate();
    }

    @Override
    public void onSetup() {
        boolean s = pad.setup;
        if (s) {
            layerLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (panelShown) showPanel(false);
            placeHint();
            updateHint();
        } else {
            layerLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        safeUpdate(layerRoot, layerLp);
        hintScroll.setVisibility(s ? View.VISIBLE : View.GONE);
        handle.setVisibility(s ? View.GONE : View.VISIBLE);
        layer.invalidate();
    }

    @Override
    public void onMessage(String s) {
        if (toast != null) toast.cancel();
        toast = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        toast.show();
    }
}
