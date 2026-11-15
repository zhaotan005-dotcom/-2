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

    static String mcziText(Activity a) {
        try {
            java.io.File d = new java.io.File(a.getApplicationInfo().nativeLibraryDir);
            long m = new java.io.File(d, "libMczi.so").length(), z = new java.io.File(d, "libzlib.so").length();
            if (m == 0) return "游戏里没有这个库";
            if (m == z) return "已关（装进游戏的是空壳）";
            return "还开着（" + (m >> 10) + " KB）。要关：ZFrame → 修补游戏 → 更新内核 + 关掉原作者窗口，最后一步要点“安装/更新”";
        } catch (Throwable e) { return "查不到：" + e; }
    }

    /** v1.26：GiveItem 编号x数量（刷物品补丁里的代码会把 x 后面的数字当数量，最多 999） */
    // v1.27 诊断：传一块自己的内存地址给补丁代码，它把走到哪一步写进来（只写这块缓冲区，不碰游戏内存）
    static java.nio.ByteBuffer dbg; static long dbgAddr;
    static long bufAddr(java.nio.ByteBuffer b) {
        try { java.lang.reflect.Field f = java.nio.Buffer.class.getDeclaredField("address"); f.setAccessible(true); return f.getLong(b); }
        catch (Throwable e) { return 0; }
    }
    static void give(Activity a, String id, int n) {
        if (dbg == null) { dbg = java.nio.ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.LITTLE_ENDIAN); dbgAddr = bufAddr(dbg); }
        for (int i = 0; i < 64; i += 4) dbg.putInt(i, 0);
        // v1.28：GiveItem 走不到人物身上；改用控制器上的 AddAvaItem（同一个入口），P 开头表示由补丁代码去找控制器的人物
        String c = "AddAvaItem P" + id + "x" + Math.max(1, n);
        if (dbgAddr > 0 && dbgAddr < 0xFFFFFFFFL) c += "x" + dbgAddr;
        Con.exec(c);
    }
    static String dbgText() {
        if (dbg == null) return "没有诊断";
        int m = dbg.getInt(0);
        if (m == 0) return dbgAddr == 0 ? "诊断缓冲区拿不到地址" : "补丁代码没被调用";
        return String.format("进入=%s 人物=%08x 背包=%08x 类型=%d 子编号=%d 数量=%d 可放=%d 结果=%d",
                m == 0x5a02 ? "走完" : "中途", dbg.getInt(4), dbg.getInt(8), dbg.getInt(12), dbg.getInt(16), dbg.getInt(20), dbg.getInt(24), dbg.getInt(28));
    }
    // 配件：枪口/握把/瞄具/弹匣/枪托（编号来自游戏里的自动拾取表和 GM 列表）
    static final String[] ATT = {"20501000001", "20501000003", "20502000001", "20502000002", "20503000001", "20503000003",
            "20503000004", "20503000005", "20503000008", "20503000009", "20504000001", "20505000001"};
    static void give(Activity a, String id, int n, String label) {
        if (giveSt == 1 || giveSt == 3) { toast(a, "先在艾挽补丁更新里点「更新」"); return; }
        give(a, id, n);
        after(a, label);
    }
    static void after(Activity a, final String lb) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            String t = dbgText(); LogCap.note("刷物品诊断 " + lb + ": " + t);
            boolean ok = dbg != null && dbg.getInt(0) == 0x5a02 && dbg.getInt(28) != 0;
            toast(a, ok ? "已刷 " + lb : lb + " 没刷出来");
        }, 1200);
    }

    static volatile int giveSt = -1; // -1 还没查，0 查不到，1 没打，2 已打
    static String giveText(final Activity a) {
        if (giveSt == -1) {
            giveSt = -2;
            new Thread(() -> { giveSt = readGive(a); LogCap.note("刷物品补丁状态 " + giveSt); }).start();
        }
        return giveSt == 2 ? "补丁已打" : giveSt == 1 || giveSt == 3 ? "先在艾挽补丁更新里点「更新」" : giveSt == 0 ? "" : "检查补丁中…";
    }
    static int readGive(Activity a) {
        int w = readWord(a, 0xe40cccL);
        if (w == 0xebf48b57) return 1;
        if (w != 0xeb23573b) return 0;
        return readWord(a, 0x17169dcL) == 0xe3560002 ? 2 : 3;
    }
    static int readWord(Activity a, final long off) {
        byte[] cur = new byte[4];
        try {
            java.io.File f = new java.io.File(a.getApplicationInfo().nativeLibraryDir, "libUE4.so");
            if (f.length() > off + 4) {
                try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) { r.seek(off); r.readFully(cur); }
            } else {
                try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(a.getApplicationInfo().sourceDir)) {
                    java.util.zip.ZipEntry e = z.getEntry("lib/armeabi-v7a/libUE4.so");
                    if (e == null) return 0;
                    try (java.io.InputStream is = z.getInputStream(e)) {
                        long left = off; byte[] b = new byte[1 << 16];
                        while (left > 0) { int r = is.read(b, 0, (int) Math.min(b.length, left)); if (r < 0) return 0; left -= r; }
                        int o = 0; while (o < 4) { int r = is.read(cur, o, 4 - o); if (r < 0) return 0; o += r; }
                    }
                }
            }
        } catch (Throwable e) { return 0; }
        return (cur[0] & 0xff) | (cur[1] & 0xff) << 8 | (cur[2] & 0xff) << 16 | (cur[3] & 0xff) << 24;
    }

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
        } else if (tab == 1) {
            head(a, u, root, "功能");
            LinearLayout r1 = row(a, root);
            btn(a, u, r1, "无敌开/关", () -> { cmd(a, "EnableCheats"); cmd(a, "God", "已切换无敌"); });
            btn(a, u, r1, "缩短等待", () -> { Patches.lastWaitScan = 0; Patches.shortenWait("手动"); toast(a, "正在缩短等待时间"); });
            LinearLayout r2 = row(a, root);
            btn(a, u, r2, "重载门/物资/车", () -> { Con.loadServerLevels("手动"); toast(a, Con.layerShort()); });
            btn(a, u, r2, "传送到安全区", () -> {
                String c = Con.circle;
                if (c == null) { toast(a, "还没拿到安全区位置，等飞机起飞后再点"); return; }
                cmd(a, "EnableCheats"); cmd(a, "BugItGo " + c + " 15000 0 0 0", "已传送到安全区中心上空");
            });
            head(a, u, root, "刷物品");
            String gt = giveText(a); if (giveSt != 2 && !gt.isEmpty()) label(a, root, gt, 12, false, SUBT);
            LinearLayout g0 = row(a, root);
            btn(a, u, g0, "一键开局装备", () -> {
                give(a, "20101000001", 1); give(a, "20600000001", 150);
                give(a, "30304000003", 1); give(a, "30305000002", 1); give(a, "30307000006", 1);
                for (String at : ATT) give(a, at, 1);
                after(a, "一键装备");
            });
            LinearLayout g1 = row(a, root);
            btn(a, u, g1, "M416", () -> give(a, "20101000001", 1, "M416"));
            btn(a, u, g1, "AK47", () -> give(a, "20101000002", 1, "AK47"));
            btn(a, u, g1, "SCAR-L", () -> give(a, "20101000004", 1, "SCAR-L"));
            LinearLayout g1b = row(a, root);
            btn(a, u, g1b, "M16A4", () -> give(a, "20101000003", 1, "M16A4"));
            btn(a, u, g1b, "UMP9", () -> give(a, "20105000001", 1, "UMP9"));
            btn(a, u, g1b, "98K", () -> give(a, "20104000001", 1, "98K"));
            LinearLayout g2 = row(a, root);
            btn(a, u, g2, "5.56子弹", () -> give(a, "20600000001", 150, "5.56子弹"));
            btn(a, u, g2, "7.62子弹", () -> give(a, "20600000002", 150, "7.62子弹"));
            btn(a, u, g2, "子弹3", () -> give(a, "20600000003", 150, "子弹3"));
            LinearLayout g3 = row(a, root);
            btn(a, u, g3, "全套配件", () -> { for (String at : ATT) give(a, at, 1); after(a, "全套配件"); });
            btn(a, u, g3, "手雷", () -> give(a, "20401000001", 3, "手雷"));
            LinearLayout g4 = row(a, root);
            btn(a, u, g4, "头盔", () -> give(a, "30304000003", 1, "头盔"));
            btn(a, u, g4, "护甲", () -> give(a, "30305000002", 1, "护甲"));
            btn(a, u, g4, "背包", () -> give(a, "30307000006", 1, "背包"));
            head(a, u, root, "刷车");
            LinearLayout v1 = row(a, root);
            btn(a, u, v1, "刷一辆车", () -> Con.spawnCar(a));
        } else {
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
