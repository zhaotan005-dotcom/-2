package com.zframe.inject;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

import com.zframe.ui.Ui;

/** 游戏内悬浮球 + 游戏菜单（进地图、日志）。直接挂在游戏 Activity 的窗口上，不需要悬浮窗权限，也不需要 root。 */
public class GameOverlay {
    static float bx = -1, by = -1;
    static boolean attached;

    static void attach(final Activity a) {
        try {
            Con.setActivity(a);
            final ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            if (decor.findViewWithTag("zframe_ball") != null) return;
            final Ui u = new Ui(a);
            final TextView ball = new TextView(a);
            ball.setTag("zframe_ball");
            ball.setText("Z");
            ball.setTextColor(0xFF0A0E24);
            ball.setTypeface(Typeface.DEFAULT_BOLD);
            ball.setTextSize(20);
            ball.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Ui.CYAN, Ui.PURPLE});
            g.setShape(GradientDrawable.OVAL);
            g.setStroke(u.dp(2), 0x88FFFFFF);
            ball.setBackground(g);
            ball.setElevation(u.dp(8));
            ball.setAlpha(0.85f);
            int s = u.dp(46);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s, s);
            lp.gravity = Gravity.TOP | Gravity.START;
            decor.addView(ball, lp);
            if (bx < 0) { bx = u.dp(12); by = u.dp(160); }
            ball.setX(bx); ball.setY(by);
            ball.setOnTouchListener(new View.OnTouchListener() {
                float dx, dy, sx, sy; boolean moved;
                public boolean onTouch(View v, MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN: dx = e.getRawX(); dy = e.getRawY(); sx = v.getX(); sy = v.getY(); moved = false; return true;
                        case MotionEvent.ACTION_MOVE:
                            float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                            if (Math.abs(mx) + Math.abs(my) > u.dp(6)) moved = true;
                            v.setX(sx + mx); v.setY(sy + my); bx = v.getX(); by = v.getY(); return true;
                        case MotionEvent.ACTION_UP:
                            if (!moved) openPanel(a); return true;
                    }
                    return false;
                }
            });
        } catch (Throwable t) {
            android.util.Log.e("ZFrame", "overlay", t);
        }
    }

    static void detach(Activity a) {
        try {
            ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
            View b = decor.findViewWithTag("zframe_ball");
            if (b != null) decor.removeView(b);
        } catch (Throwable ignored) {}
    }

    static Dialog dialog;

    static void btn(Ui u, LinearLayout row, String label, final Runnable r) {
        TextView b = u.ghost(row, label, v -> r.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.setMargins(0, u.dp(3), u.dp(6), u.dp(3));
        b.setLayoutParams(lp);
    }

    static void cmd(Activity a, String c) { cmd(a, c, null); }

    static void cmd(Activity a, String c, String label) {
        boolean ok = Con.exec(c);
        if (label != null) Toast.makeText(a, ok ? label : "执行失败", Toast.LENGTH_SHORT).show();
    }

    static void openPanel(final Activity a) {
        if (dialog != null && dialog.isShowing()) return;
        final Ui u = new Ui(a);
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = u.col();
        root.setBackground(u.round(Ui.BG, 20));
        root.setPadding(u.dp(16), u.dp(14), u.dp(16), u.dp(12));
        LinearLayout head = u.row();
        root.addView(head);
        u.text(head, "ZFrame 游戏菜单", 18, Ui.TEXT, true).setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = u.ghost(head, "收起", v -> d.dismiss());
        close.setLayoutParams(new LinearLayout.LayoutParams(u.dp(72), -2));
        u.sub(root, "内核 " + ZInit.CORE_VER + " · " + a.getPackageName());

        u.text(root, "修复", 15, Ui.TEXT, true).setPadding(0, u.dp(10), 0, u.dp(4));
        u.sub(root, Patches.Status.text());
        u.sub(root, "开局等待：" + Patches.waitStatus);
        u.sub(root, "声音引擎：" + Patches.audioStatus);
        u.sub(root, "声音修复：" + Con.audioFix);
        u.sub(root, "声音诊断日志：" + AkDiag.status);
        LinearLayout ra = u.row(); root.addView(ra);
        btn(u, ra, "经大厅重进本局(修声音)", () -> Toast.makeText(a, Con.audioBounceNow() ? "马上进大厅初始化声音，然后自动回到地图" : Con.audioFix, Toast.LENGTH_LONG).show());
        btn(u, ra, Con.audioAuto ? "自动修声音：开" : "自动修声音：关", () -> { Con.setAudioAuto(!Con.audioAuto); Toast.makeText(a, Con.audioAuto ? "已开启：每次启动第一次进图会先经过大厅" : "已关闭自动修声音", Toast.LENGTH_SHORT).show(); d.dismiss(); });
        final TextView ls = u.sub(root, "门/物资/车刷新层：" + Con.layerStatus());
        u.ghost(root, "重新加载门/物资/车刷新层", v -> { Con.loadServerLevels("手动"); ls.setText("门/物资/车刷新层：" + Con.layerStatus()); });

        u.text(root, "功能", 15, Ui.TEXT, true).setPadding(0, u.dp(10), 0, u.dp(4));
        LinearLayout r1 = u.row(); root.addView(r1);
        btn(u, r1, "无敌开/关", () -> { cmd(a, "EnableCheats"); cmd(a, "God", "已切换无敌"); });
        btn(u, r1, "缩短本局等待", () -> { Patches.lastWaitScan = 0; Patches.shortenWait("手动"); Toast.makeText(a, "正在缩短等待时间", Toast.LENGTH_SHORT).show(); });
        u.text(root, "帧率（当前 " + (Con.fps > 0 ? Con.fps + " 帧" : "游戏默认") + "）", 15, Ui.TEXT, true).setPadding(0, u.dp(10), 0, u.dp(4));
        LinearLayout rf = u.row(); root.addView(rf);
        for (final int f : new int[]{60, 90, 120}) btn(u, rf, f + "帧", () -> { Con.setFps(f); Toast.makeText(a, "已设为 " + f + " 帧，以后自动生效", Toast.LENGTH_SHORT).show(); });
        btn(u, rf, "默认", () -> { Con.setFps(0); Con.exec("t.MaxFPS 40"); Toast.makeText(a, "已恢复游戏默认", Toast.LENGTH_SHORT).show(); });
        u.ghost(root, "跳伞后用：传送到安全区中心上空", v -> {
            String c = Con.circle;
            if (c == null) { Toast.makeText(a, "还没拿到安全区位置，等飞机起飞后再点", Toast.LENGTH_LONG).show(); return; }
            cmd(a, "EnableCheats"); cmd(a, "BugItGo " + c + " 15000 0 0 0", "已传送到安全区中心上空");
        });

        u.text(root, "控制台", 15, Ui.TEXT, true).setPadding(0, u.dp(10), 0, u.dp(4));
        LinearLayout r4 = u.row(); root.addView(r4);
        final EditText in = u.input(r4, "输入引擎命令", false);
        in.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1));
        TextView go = u.primary(r4, "执行", v -> { String c = in.getText().toString().trim(); if (!c.isEmpty()) cmd(a, c, "已执行"); });
        go.setLayoutParams(new LinearLayout.LayoutParams(u.dp(80), -2));

        u.text(root, "日志", 15, Ui.TEXT, true).setPadding(0, u.dp(12), 0, u.dp(4));
        final TextView st = u.sub(root, LogCap.status());
        u.primary(root, "立即保存日志", v -> new Thread(() -> {
            LogCap.syncNow(a);
            a.runOnUiThread(() -> { st.setText(LogCap.status()); Toast.makeText(a, "已保存到 下载/ZFrame", Toast.LENGTH_SHORT).show(); });
        }).start());

        ScrollView sv = new ScrollView(a);
        sv.addView(root);
        d.setContentView(sv);
        Window w = d.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        int width = Math.min(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        int hgt = Math.min(a.getResources().getDisplayMetrics().widthPixels, a.getResources().getDisplayMetrics().heightPixels);
        w.setLayout((int) (width * 0.9f), (int) (hgt * 0.92f));
        w.setDimAmount(0.3f);
        d.show();
        dialog = d;
    }
}
