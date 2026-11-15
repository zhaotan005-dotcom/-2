package com.zframe.inject;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import com.zframe.ui.Ui;

/**
 * v1.23 艾挽：游戏里的常驻小窗（仿原作者窗口的布局）——标题栏可拖动、点 ▼/▶ 折叠，
 * 左边一列分页（地图/功能/设置），右边是内容。挂在游戏 Activity 的窗口上，不需要悬浮窗权限，也不需要 root。
 */
public class GameOverlay {
    static float px = -1, py = -1;
    static boolean collapsed = false;
    static int tab = 0;

    static final int BG = 0xE6EEF2FA, HEAD = 0xF0DCE4F4, TXT = 0xFF1E2433, SUBT = 0xFF4A5470,
            TAB = 0xFFA9BDE8, TAB_ON = 0xFF6F8FD6, BTN = 0xFFB7CAF1, ACCENT = 0xFF1FA67A;

    static GradientDrawable box(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(r);
        return g;
    }

    static void attach(final Activity a) {
        try {
            Con.setActivity(a);
            final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            if (decor.findViewWithTag("zframe_panel") != null) return;
            final Ui u = new Ui(a);

            final LinearLayout panel = new LinearLayout(a);
            panel.setTag("zframe_panel");
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setBackground(box(BG, u.dp(6)));
            panel.setElevation(u.dp(6));

            // 标题栏
            LinearLayout head = new LinearLayout(a);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setBackground(box(HEAD, u.dp(6)));
            head.setPadding(u.dp(10), u.dp(6), u.dp(10), u.dp(6));
            final TextView arrow = new TextView(a);
            arrow.setTextColor(TXT); arrow.setTextSize(15); arrow.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(arrow, new LinearLayout.LayoutParams(u.dp(28), -2));
            TextView title = new TextView(a);
            title.setText("艾挽 · 全军出击单机版");
            title.setTextColor(TXT); title.setTextSize(15); title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setGravity(Gravity.CENTER);
            head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            panel.addView(head, new LinearLayout.LayoutParams(-1, -2));

            // 主体：左边分页，右边内容
            final LinearLayout body = new LinearLayout(a);
            body.setOrientation(LinearLayout.HORIZONTAL);
            body.setPadding(u.dp(6), u.dp(6), u.dp(6), u.dp(6));
            LinearLayout side = new LinearLayout(a);
            side.setOrientation(LinearLayout.VERTICAL);
            TextView logo = new TextView(a);
            logo.setText("艾挽"); logo.setTextColor(ACCENT); logo.setTextSize(20); logo.setTypeface(Typeface.DEFAULT_BOLD);
            logo.setGravity(Gravity.CENTER);
            logo.setPadding(0, u.dp(6), 0, u.dp(10));
            side.addView(logo, new LinearLayout.LayoutParams(-1, -2));
            final ScrollView sv = new ScrollView(a);
            final LinearLayout content = new LinearLayout(a);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(u.dp(8), 0, u.dp(2), 0);
            sv.addView(content);
            final String[] tabs = {"地图", "功能", "设置"};
            final TextView[] tv = new TextView[tabs.length];
            for (int k = 0; k < tabs.length; k++) {
                final int idx = k;
                TextView t = new TextView(a);
                t.setText(tabs[k]); t.setTextColor(TXT); t.setTextSize(14); t.setTypeface(Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER);
                t.setPadding(0, u.dp(10), 0, u.dp(10));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.setMargins(0, 0, 0, u.dp(4));
                side.addView(t, lp);
                tv[k] = t;
                t.setOnClickListener(v -> { tab = idx; paintTabs(tv, u); fill(a, u, content); });
            }
            paintTabs(tv, u);
            body.addView(side, new LinearLayout.LayoutParams(u.dp(78), -1));
            body.addView(sv, new LinearLayout.LayoutParams(0, -1, 1));
            panel.addView(body, new LinearLayout.LayoutParams(-1, u.dp(230)));
            fill(a, u, content);

            Runnable apply = () -> { body.setVisibility(collapsed ? View.GONE : View.VISIBLE); arrow.setText(collapsed ? "▶" : "▼"); };
            apply.run();

            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(u.dp(340), -2);
            flp.gravity = Gravity.TOP | Gravity.START;
            decor.addView(panel, flp);
            if (px < 0) { px = u.dp(40); py = u.dp(40); }
            panel.setX(px); panel.setY(py);

            // 拖标题栏移动；点一下折叠/展开
            head.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy, sx, sy; boolean moved;
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = panel.getX(); sy = panel.getY(); moved = false; return true;
                        case MotionEvent.ACTION_MOVE:
                            float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                            if (Math.abs(mx) + Math.abs(my) > u.dp(6)) moved = true;
                            if (moved) { panel.setX(sx + mx); panel.setY(sy + my); px = panel.getX(); py = panel.getY(); }
                            return true;
                        case MotionEvent.ACTION_UP:
                            if (!moved) { collapsed = !collapsed; apply.run(); if (!collapsed) fill(a, u, content); }
                            return true;
                    }
                    return false;
                }
            });
        } catch (Throwable t) {
            android.util.Log.e("ZFrame", "overlay", t);
        }
    }

    static void paintTabs(TextView[] tv, Ui u) {
        for (int j = 0; j < tv.length; j++) tv[j].setBackground(box(j == tab ? TAB_ON : TAB, u.dp(4)));
    }

    static void detach(Activity a) {
        try {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            View b = decor.findViewWithTag("zframe_panel");
            if (b != null) decor.removeView(b);
        } catch (Throwable ignored) {}
    }

    static TextView label(Activity a, ViewGroup p, String s, float size, boolean bold, int color) {
        TextView t = new TextView(a);
        t.setText(s); t.setTextSize(size); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, 0, 0, 0);
        p.addView(t, new LinearLayout.LayoutParams(-1, -2));
        return t;
    }

    static LinearLayout row(Activity a, ViewGroup p) {
        LinearLayout r = new LinearLayout(a);
        r.setOrientation(LinearLayout.HORIZONTAL);
        p.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    static TextView btn(Activity a, Ui u, LinearLayout row, String label, final Runnable r) {
        TextView b = new TextView(a);
        b.setText(label); b.setTextColor(TXT); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(u.dp(4), u.dp(10), u.dp(4), u.dp(10));
        b.setBackground(box(BTN, u.dp(4)));
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, u.dp(3), u.dp(6), u.dp(3));
        row.addView(b, lp);
        return b;
    }

    static void head(Activity a, Ui u, ViewGroup p, String s) {
        TextView t = label(a, p, "● " + s, 14, true, TXT);
        t.setPadding(0, u.dp(6), 0, u.dp(2));
    }

    static void cmd(Activity a, String c) { cmd(a, c, null); }

    static void cmd(Activity a, String c, String label) {
        boolean ok = Con.exec(c);
        if (label != null) Toast.makeText(a, ok ? label : "执行失败", Toast.LENGTH_SHORT).show();
    }

    static void toast(Activity a, String msg) { Toast.makeText(a, msg, Toast.LENGTH_SHORT).show(); }

    static void fill(final Activity a, final Ui u, final LinearLayout root) {
        root.removeAllViews();
        if (tab == 0) {
            head(a, u, root, "地图选择");
            LinearLayout m1 = row(a, root);
            btn(a, u, m1, "海岛地图", () -> toast(a, Con.goMap(Con.MAP_ERANGEL)));
            btn(a, u, m1, "海岛地图2", () -> toast(a, Con.goMap(Con.MAP_ERANGEL2)));
            LinearLayout m2 = row(a, root);
            btn(a, u, m2, "雨林地图", () -> toast(a, Con.goMap(Con.MAP_SAVAGE)));
            btn(a, u, m2, "进入大厅", () -> toast(a, Con.goLobby()));
            label(a, root, "声音：" + Con.audioFix, 12, false, SUBT).setPadding(0, u.dp(6), 0, 0);
        } else if (tab == 1) {
            head(a, u, root, "功能");
            LinearLayout r1 = row(a, root);
            btn(a, u, r1, "无敌开/关", () -> { cmd(a, "EnableCheats"); cmd(a, "God", "已切换无敌"); });
            btn(a, u, r1, "缩短等待", () -> { Patches.lastWaitScan = 0; Patches.shortenWait("手动"); toast(a, "正在缩短等待时间"); });
            LinearLayout r2 = row(a, root);
            btn(a, u, r2, "重载门/物资/车", () -> { Con.loadServerLevels("手动"); toast(a, Con.layerStatus()); });
            btn(a, u, r2, "传送到安全区", () -> {
                String c = Con.circle;
                if (c == null) { toast(a, "还没拿到安全区位置，等飞机起飞后再点"); return; }
                cmd(a, "EnableCheats"); cmd(a, "BugItGo " + c + " 15000 0 0 0", "已传送到安全区中心上空");
            });
            label(a, root, "门/物资/车：" + Con.layerStatus(), 12, false, SUBT).setPadding(0, u.dp(6), 0, 0);
            label(a, root, "开局等待：" + Patches.waitStatus, 12, false, SUBT);
        } else {
            head(a, u, root, "声音");
            LinearLayout ra = row(a, root);
            btn(a, u, ra, Con.audioAuto ? "开机先进大厅：开" : "开机先进大厅：关", () -> { Con.setAudioAuto(!Con.audioAuto); fill(a, u, root); });
            head(a, u, root, "画质帧率");
            label(a, root, Qcc.status + "（在游戏自带的设置里选）", 12, false, SUBT);
            head(a, u, root, "控制台");
            LinearLayout r4 = row(a, root);
            final EditText in = new EditText(a);
            in.setHint("引擎命令"); in.setTextColor(TXT); in.setHintTextColor(SUBT); in.setTextSize(13);
            in.setSingleLine(true); in.setInputType(InputType.TYPE_CLASS_TEXT);
            in.setBackground(box(0xFFFFFFFF, u.dp(4)));
            in.setPadding(u.dp(8), u.dp(6), u.dp(8), u.dp(6));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, -2, 2);
            ilp.setMargins(0, u.dp(3), u.dp(6), u.dp(3));
            r4.addView(in, ilp);
            btn(a, u, r4, "执行", () -> { String c = in.getText().toString().trim(); if (!c.isEmpty()) cmd(a, c, "已执行"); });
            head(a, u, root, "日志");
            final TextView st = label(a, root, LogCap.status(), 12, false, SUBT);
            LinearLayout r5 = row(a, root);
            btn(a, u, r5, "立即保存日志", () -> new Thread(() -> {
                LogCap.syncNow(a);
                a.runOnUiThread(() -> { st.setText(LogCap.status()); toast(a, "已保存到 下载/ZFrame"); });
            }).start());
            label(a, root, "内核 " + ZInit.CORE_VER, 11, false, SUBT).setPadding(0, u.dp(6), 0, 0);
        }
    }
}
