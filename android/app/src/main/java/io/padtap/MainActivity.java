package io.padtap;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Setup screen: Shizuku / overlay permission, start & stop, profiles. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF0D0F17, CARD = 0xFF161926, LINE = 0xFF2A2E40, TX = 0xFFE6E8F2,
            MUT = 0xFF9AA3B8, AC = 0xFF3B7CFF, OK = 0xFF7EE2A8, WARN = 0xFFFFD866;

    private Pad pad;
    private LinearLayout root;
    private float dp;
    private final Runnable watcher = this::render;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        pad = Pad.get(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(18), px(28), px(18), px(40));
        sv.addView(root);
        setContentView(sv);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
    }

    @Override
    protected void onResume() {
        super.onResume();
        pad.watchers.add(watcher);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pad.watchers.remove(watcher);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.25f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable round(int color, float r, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r * dp);
        if (stroke != 0) g.setStroke(px(1), stroke);
        return g;
    }

    private Button button(String s, boolean primary, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(round(primary ? AC : 0xFF2A2E40, 12, primary ? AC : 0xFF3A3F56));
        b.setStateListAnimator(null);
        b.setPadding(px(16), px(12), px(16), px(12));
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout card(String title, String state, int stateColor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(14), px(16), px(14));
        c.setBackground(round(CARD, 14, LINE));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(text(title, 16, TX, true), new LinearLayout.LayoutParams(0, -2, 1f));
        if (state != null) head.addView(text(state, 13, stateColor, true));
        c.addView(head);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(14);
        root.addView(c, lp);
        return c;
    }

    private void add(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(topDp);
        parent.addView(v, lp);
    }

    private void render() {
        root.removeAllViews();
        root.addView(text("🎮 PadTap", 28, TX, true));
        add(root, text("Bluetoothコントローラーで、画面をタップ操作するアプリ。ブラウザゲームでも普通のアプリでも使える。", 14, MUT, false), 6);

        // 1. Shizuku
        boolean installed = pad.shizukuInstalled();
        boolean runningS = pad.shizukuRunning();
        boolean granted = pad.shizukuGranted();
        LinearLayout c1 = card("① Shizuku", granted ? "OK" : "未設定", granted ? OK : WARN);
        if (!installed && !runningS) {
            add(c1, text("コントローラーを直接読んで本物のタッチを送るために「Shizuku」というアプリを使うよ。まずインストールしてね。", 14, MUT, false), 8);
            add(c1, button("Shizukuを入手", true, v -> openUrl("market://details?id=" + Pad.SHIZUKU_PKG,
                    "https://play.google.com/store/apps/details?id=" + Pad.SHIZUKU_PKG)), 10);
        } else if (!runningS) {
            add(c1, text("Shizukuが起動してない。Shizukuを開いて「ワイヤレスデバッグ」で開始してね。スマホを再起動したら毎回これが必要。", 14, MUT, false), 8);
            add(c1, button("Shizukuを開く", true, v -> openShizuku()), 10);
        } else if (pad.shizukuOld()) {
            add(c1, text("Shizukuが古いみたい。最新版に更新してね。", 14, MUT, false), 8);
        } else if (!granted) {
            add(c1, text("ShizukuでPadTapを許可してね。", 14, MUT, false), 8);
            add(c1, button("許可する", true, v -> pad.requestShizuku()), 10);
        } else {
            add(c1, text("Shizukuは準備OK。スマホを再起動したらShizukuの開始だけやり直してね。", 14, MUT, false), 8);
        }

        // 2. Overlay
        boolean overlay = Settings.canDrawOverlays(this);
        LinearLayout c2 = card("② 他のアプリの上に表示", overlay ? "OK" : "未設定", overlay ? OK : WARN);
        if (!overlay) {
            add(c2, text("カーソルや配置マークを画面に出すために必要。設定画面でPadTapをONにしてね。", 14, MUT, false), 8);
            add(c2, button("設定を開く", true, v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())))), 10);
        }

        // 3. Notifications (optional)
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            LinearLayout c3 = card("③ 通知（おすすめ）", null, 0);
            add(c3, text("通知からメニューを開いたり停止したりできるようになる。", 14, MUT, false), 8);
            add(c3, button("通知を許可", false, v -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2)), 10);
        }

        // Start / stop
        boolean ready = granted && overlay;
        boolean on = OverlayService.running;
        Button go = button(on ? "停止する" : "開始する", !on, v -> {
            if (OverlayService.running) OverlayService.stop(this);
            else OverlayService.start(this);
            root.postDelayed(this::render, 300);
        });
        go.setEnabled(ready || on);
        go.setAlpha(ready || on ? 1f : 0.4f);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        add(root, go, 18);

        if (on) {
            String st;
            if (pad.svc == null) st = pad.binding ? "サービス起動中…" : "サービス未接続";
            else if (pad.devices.isEmpty()) st = "コントローラー未検出：ボタンを押すか、Bluetoothでつなぎ直してみて";
            else st = "コントローラー: " + pad.devices.replace("\n", " / ");
            add(root, text(st, 13, pad.devices.isEmpty() ? WARN : OK, false), 10);
        }
        if (!pad.lastError.isEmpty()) add(root, text("エラー: " + pad.lastError, 12, 0xFFFF8A8A, false), 6);

        // Profiles
        LinearLayout cp = card("プロファイル（ゲームごとの配置）", null, 0);
        for (int i = 0; i < pad.cfg.profiles.size(); i++) {
            final int idx = i;
            Config.Profile p = pad.cfg.profiles.get(i);
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, px(6), 0, px(6));
            boolean cur = i == pad.cfg.current;
            TextView name = text((cur ? "● " : "○ ") + p.name + "   " + p.pos.size() + "個", 15, cur ? TX : MUT, cur);
            name.setOnClickListener(v -> {
                pad.cfg.current = idx;
                pad.cfg.save();
                pad.releaseAll();
                render();
            });
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            Button ren = button("名前", false, v -> rename(idx));
            ren.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            ren.setPadding(px(12), px(6), px(12), px(6));
            r.addView(ren);
            if (pad.cfg.profiles.size() > 1) {
                Button del = button("削除", false, v -> new AlertDialog.Builder(this)
                        .setMessage("「" + p.name + "」を削除する？")
                        .setPositiveButton("削除", (d, w) -> {
                            pad.cfg.profiles.remove(idx);
                            if (pad.cfg.current >= idx && pad.cfg.current > 0) pad.cfg.current--;
                            pad.cfg.save();
                            render();
                        })
                        .setNegativeButton("やめる", null).show());
                del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                del.setPadding(px(12), px(6), px(12), px(6));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
                dl.leftMargin = px(6);
                r.addView(del, dl);
            }
            cp.addView(r);
        }
        add(cp, button("＋ プロファイルを追加", false, v -> {
            pad.cfg.profiles.add(pad.cfg.newProfile());
            pad.cfg.save();
            render();
        }), 8);

        // Help
        LinearLayout ch = card("操作", null, 0);
        add(ch, text("・左スティック / 十字キー：カーソル移動（右スティックは微調整）\n"
                + "・A：カーソル位置をタップ（押しっぱなしで長押し、動かすとドラッグ）\n"
                + "・配置したボタン：決めた場所をタップ（同時押しはマルチタッチ）\n"
                + "・SELECT＋START：ボタン配置の設定を開く／閉じる\n\n"
                + "配置の設定：コントローラーのボタンを押す → 画面の押したい場所をタップ。マークはドラッグで動かせる。\n"
                + "画面右の🎮からメニュー（プロファイル切り替え・速度など）。", 14, MUT, false), 8);

        add(root, text("v" + BuildConfig.VERSION_NAME, 11, 0xFF5A6278, false), 18);
    }

    private void rename(int idx) {
        Config.Profile p = pad.cfg.profiles.get(idx);
        EditText et = new EditText(this);
        et.setText(p.name);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(px(20), px(8), px(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("プロファイル名")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (!s.isEmpty()) {
                        p.name = s;
                        pad.cfg.save();
                    }
                    render();
                })
                .setNegativeButton("やめる", null)
                .show();
    }

    private void openShizuku() {
        Intent i = getPackageManager().getLaunchIntentForPackage(Pad.SHIZUKU_PKG);
        if (i != null) startActivity(i);
    }

    private void openUrl(String primary, String fallback) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(primary)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        render();
    }
}
