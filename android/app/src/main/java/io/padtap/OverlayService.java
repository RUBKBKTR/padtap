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
    private LinearLayout hint;
    private TextView hintTitle, hintSub;
    private LinearLayout hintRow;

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

        hint = new LinearLayout(this);
        hint.setOrientation(LinearLayout.VERTICAL);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        hint.setPadding(px(14), px(10), px(14), px(10));
        hint.setBackground(round(0xF2101420, 14, 0xFF444A66));
        hintTitle = text("", 15, Color.WHITE, true);
        hintTitle.setGravity(Gravity.CENTER);
        hintSub = text("", 12, 0xFF99AABB, false);
        hintSub.setGravity(Gravity.CENTER);
        hintRow = new LinearLayout(this);
        hintRow.setOrientation(LinearLayout.HORIZONTAL);
        hintRow.setGravity(Gravity.CENTER);
        hint.addView(hintTitle);
        hint.addView(hintSub, margins(-2, -2, 0, 2, 0, 0));
        hint.addView(hintRow, margins(-2, -2, 0, 8, 0, 0));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hp.topMargin = px(36);
        hp.leftMargin = px(10);
        hp.rightMargin = px(10);
        layerRoot.addView(hint, hp);
        hint.setVisibility(View.GONE);

        Point sz = realSize();
        layerLp = lp(sz.x, sz.y, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        // Android 12+: touches only pass through overlays at <= 80% opacity.
        layerLp.alpha = 0.8f;
        wm.addView(layerRoot, layerLp);
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

    private final class Layer extends View {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        final List<Rip> ripples = new ArrayList<>();
        int hitCode = -1;
        long hitAt;
        int drag = -1;
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
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            pad.setSurface(getWidth(), getHeight(), loc[0], loc[1]);
        }

        @Override
        protected void onDraw(Canvas c) {
            int W = getWidth(), H = getHeight();
            long now = SystemClock.uptimeMillis();
            if (pad.setup) c.drawColor(0x77080A12);
            Config.Profile p = pad.cfg.p();
            if (pad.setup || pad.cfg.showMarks) {
                for (Map.Entry<Integer, float[]> e : p.pos.entrySet()) {
                    int code = e.getKey();
                    float x = e.getValue()[0] * W, y = e.getValue()[1] * H;
                    if (code == drag && moved) {
                        x = dx;
                        y = dy;
                    }
                    boolean sel = code == pad.sel;
                    boolean hit = code == hitCode && now - hitAt < 120;
                    float r = 24 * dp * (sel ? 1.15f : hit ? 0.85f : 1f);
                    fill.setColor(sel ? 0x55FFDC64 : hit ? 0x9978C8FF : 0x30FFFFFF);
                    c.drawCircle(x, y, r, fill);
                    stroke.setColor(sel ? 0xFFFFDD66 : 0xFF77CCFF);
                    c.drawCircle(x, y, r, stroke);
                    c.drawText(Config.label(code), x, y + label.getTextSize() * 0.35f, label);
                }
            }
            for (Iterator<Rip> it = ripples.iterator(); it.hasNext(); ) {
                Rip r = it.next();
                float t = (now - r.t) / 350f;
                if (t >= 1) {
                    it.remove();
                    continue;
                }
                rp.setAlpha((int) (255 * (1 - t)));
                c.drawCircle(r.x, r.y, 20 * dp * (0.4f + 0.9f * t), rp);
            }
            if (pad.cursorVisible()) {
                float x = pad.curX, y = pad.curY;
                boolean held = !pad.cursorHeld.isEmpty();
                float r = 14 * dp * (held ? 0.8f : 1f);
                if (held) {
                    fill.setColor(0x8C78C8FF);
                    c.drawCircle(x, y, r, fill);
                }
                stroke.setColor(0x8C000000);
                stroke.setStrokeWidth(5 * dp);
                c.drawCircle(x, y, r, stroke);
                stroke.setColor(Color.WHITE);
                stroke.setStrokeWidth(2 * dp);
                c.drawCircle(x, y, r, stroke);
                fill.setColor(Color.WHITE);
                c.drawCircle(x, y, 2.5f * dp, fill);
            }
            if (!ripples.isEmpty() || (hitCode >= 0 && now - hitAt < 150)) postInvalidateOnAnimation();
        }

        private int findMark(float x, float y) {
            int best = -1;
            float bd = 32 * dp;
            for (Map.Entry<Integer, float[]> e : pad.cfg.p().pos.entrySet()) {
                float d = (float) Math.hypot(e.getValue()[0] * getWidth() - x, e.getValue()[1] * getHeight() - y);
                if (d < bd) {
                    bd = d;
                    best = e.getKey();
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
                    if (drag < 0) {
                        if (pad.sel >= 0) pad.place(pad.sel, x, y);
                        else pad.msg("先にコントローラーのボタンを押してね");
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (drag >= 0) {
                        if (Math.hypot(x - sx, y - sy) > 6 * dp) moved = true;
                        if (moved) {
                            dx = x;
                            dy = y;
                            invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drag >= 0) {
                        if (moved) {
                            pad.cfg.p().setPos(drag, x / getWidth(), y / getHeight());
                            pad.cfg.save();
                        } else {
                            pad.sel = drag;
                        }
                        drag = -1;
                        moved = false;
                        onSetup();
                    }
                    return true;
                default:
                    return true;
            }
        }
    }

    private void updateHint() {
        hintRow.removeAllViews();
        Config.Profile p = pad.cfg.p();
        LinearLayout.LayoutParams bp = margins(-2, -2, 3, 0, 3, 0);
        if (pad.sel < 0) {
            hintTitle.setText("割り当てたいボタンをコントローラーで押してね");
            StringBuilder cs = new StringBuilder();
            for (int c : p.cursor) {
                if (cs.length() > 0) cs.append(", ");
                cs.append(Config.label(c));
            }
            hintSub.setText("マークはドラッグで移動／タップで選択"
                    + (cs.length() > 0 ? "\nカーソル位置を押す: " + cs : "")
                    + "\nプロファイル: " + p.name);
            hintRow.addView(btn("メニュー", 0, v -> {
                pad.setSetup(false);
                showPanel(true);
            }), bp);
            hintRow.addView(btn("完了", 1, v -> pad.setSetup(false)), bp);
        } else {
            final int s = pad.sel;
            String n = Config.label(s);
            hintTitle.setText(n + " を置く場所をタップ");
            hintSub.setText("もう一度 " + n + " を押すとカーソル位置に置くよ"
                    + (p.cursor.contains(s) ? "\n（今: カーソル位置を押すボタン）" : ""));
            hintRow.addView(btn("カーソル用にする", 0, v -> pad.setCursorBtn(s)), bp);
            if (p.has(s)) hintRow.addView(btn("解除", 2, v -> pad.clearBtn(s)), bp);
            hintRow.addView(btn("戻る", 0, v -> {
                pad.sel = -1;
                onSetup();
            }), bp);
            hintRow.addView(btn("完了", 1, v -> pad.setSetup(false)), bp);
        }
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

        LinearLayout sr = row();
        sr.addView(text("カーソル速度", 14, 0xFFE6E8F2, false));
        SeekBar sb = new SeekBar(themed);
        sb.setMax(27);
        sb.setProgress(Math.round(pad.cfg.speed * 10) - 3);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                pad.cfg.speed = (v + 3) / 10f;
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                pad.cfg.save();
            }
        });
        sr.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(sr);

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
        Config.Profile p = pad.cfg.p();
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
            updateHint();
        } else {
            layerLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        safeUpdate(layerRoot, layerLp);
        hint.setVisibility(s ? View.VISIBLE : View.GONE);
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
