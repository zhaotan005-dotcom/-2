package com.zframe.inject;

import android.app.Activity;

import java.io.*;
import java.lang.reflect.Method;
import java.util.*;

/**
 * 通过 UE4 自带的 GameActivity.nativeConsoleCommand 给引擎发控制台命令（纯 Java，不需要 root）。
 * 另外盯着引擎日志：地图里一出现 “StreamingServerLayer Add 某关卡”，就自动用 StreamLevelIn 把这些关卡
 * （门、物资刷新点、载具刷新点……）真正加载出来。
 */
public class Con {
    static volatile Activity act;
    static volatile String last = "";
    static final List<String> history = Collections.synchronizedList(new ArrayList<String>());

    static void setActivity(Activity a) { act = a; }

    static Method find(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod("nativeConsoleCommand", String.class); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    /** 发一条命令，返回是否送达引擎 */
    public static boolean exec(String cmd) {
        Activity a = act;
        if (a == null) { last = "没找到游戏界面"; return false; }
        try {
            Method m = find(a.getClass());
            if (m == null) { last = "这个游戏没有 UE4 控制台入口"; return false; }
            m.invoke(a, cmd);
            last = cmd;
            history.add(cmd);
            if (history.size() > 50) history.remove(0);
            LogCap.note("命令: " + cmd);
            return true;
        } catch (Throwable e) {
            last = "发送失败 " + e;
            LogCap.note("命令失败: " + cmd + " " + e);
            return false;
        }
    }

    // ===== 自动加载 ServerOnly 关卡 =====
    static final LinkedHashSet<String> serverLevels = new LinkedHashSet<>();
    static volatile String layerStatus = "等待进地图";
    static volatile long lastAddAt, sentAt; static volatile int passes;
    static volatile boolean autoOn = true;

    public static String layerStatus() { return layerStatus; }

    static void loadServerLevels(String why) {
        List<String> lv;
        synchronized (serverLevels) { lv = new ArrayList<>(serverLevels); }
        if (lv.isEmpty()) { layerStatus = "还没在日志里看到服务器关卡（先进一局）"; return; }
        exec("EnableCheats");
        int n = 0;
        for (String p : lv) {
            if (p.endsWith("/WorldMeshesLevel") || p.endsWith("/NavigationSubLevel")) continue; // 只给服务器用的大网格/导航，跳过
            if (exec("StreamLevelIn " + p)) n++;
        }
        sentAt = System.currentTimeMillis();
        layerStatus = why + "：已请求加载 " + n + " 个关卡（门/物资/载具点）";
    }

    static void startWatcher(final android.content.Context ctx) {
        Thread t = new Thread(new Runnable() { public void run() {
            File cur = null; long pos = 0; StringBuilder part = new StringBuilder();
            while (true) {
                try {
                    if (cur == null || !cur.exists() || cur.length() < pos) {
                        cur = findUeLog(ctx); pos = 0; part.setLength(0);
                        // v1.18：上一局留下的旧日志不读（以前会把旧日志当成“引擎已启动”，引擎没起来就发命令→闪退）
                        if (cur != null && cur.lastModified() < bootAt) pos = cur.length();
                    }
                    if (cur != null && cur.length() > pos) {
                        try (RandomAccessFile f = new RandomAccessFile(cur, "r")) {
                            f.seek(pos);
                            byte[] b = new byte[(int) Math.min(1 << 20, cur.length() - pos)];
                            f.readFully(b);
                            pos += b.length;
                            boolean xor = !plain;
                            if (pos == b.length) { plain = new String(b, 0, Math.min(b.length, 4000), "ISO-8859-1").contains("Log"); xor = !plain; }
                            if (xor) for (int i = 0; i < b.length; i++) b[i] ^= 0x73;
                            part.append(new String(b, "UTF-8"));
                            int nl;
                            while ((nl = part.indexOf("\n")) >= 0) { line(part.substring(0, nl)); part.delete(0, nl + 1); }
                        }
                    }
                    long now = System.currentTimeMillis();
                    // 只有新日志里出现 “LogLoad: Took” 才算引擎真正就绪
                    tickTweaks(now);
                    if (autoOn && lastAddAt > 0 && now - lastAddAt > 3000 && sentAt < lastAddAt) { passes = 1; loadServerLevels("自动"); }
                    else if (autoOn && passes == 1 && sentAt > lastAddAt && now - sentAt > 15000) { passes = 2; loadServerLevels("自动(第2遍)"); }
                } catch (Throwable e) { LogCap.note("日志监视出错 " + e); }
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }}, "zframe-uelog");
        t.setDaemon(true);
        t.start();
    }

    static boolean plain;

    // ===== 用引擎 SET 命令改类默认值（新对象和已存在的对象都会改） =====
    static final String[] TWEAKS = {
        "set NZBRGameState WaitingPlayersCountDownTime 5",
        "set NZBRGameState StartMatchCountDownTime 5",
        "set NZBRGameState WaitingSkydivingCountDownTime 1",
        "set NZBRLGameState WaitingPlayersCountDownTime 5",
        "set NZBRLGameState StartMatchCountDownTime 5",
        "set NZSceneDoorManager StandaloneUnloadAllDoor 0",
        "set NZGameMode bEnableAIDefaultWeap 1",
        "set NZGameMode bEnableGiveBotEquip 1",
        "set NZBattleRoyaleGame MaxBotLimit 30",
        "getall NZBRGameState WaitingPlayersCountDownTime",
        "getall NZBRGameState StartMatchCountDownTime",
        "getall NZSceneDoorManager StandaloneUnloadAllDoor",
        "getall NZBattleRoyaleGame MaxBotLimit",
    };
    static volatile long engineSeenAt, tweakAt, browseAt;
    static volatile int tweakDone; static volatile long waitAt, brAt;
    public static volatile String tweakStatus = "等待游戏启动";

    // ===== 帧率 =====
    public static volatile int fps = 0; // 0 = 不改
    static volatile long fpsAt;

    static void loadFps() {
        Activity a = act; if (a == null) return;
        fps = a.getSharedPreferences("zframe", 0).getInt("fps", 0);
    }

    public static void setFps(int f) {
        fps = f;
        Activity a = act;
        if (a != null) a.getSharedPreferences("zframe", 0).edit().putInt("fps", f).apply();
        applyFps();
    }

    static void applyFps() {
        final int f = fps; if (f <= 0) return;
        exec("t.MaxFPS " + f);
        final Activity a = act;
        if (a != null) a.runOnUiThread(() -> { try {
            android.view.Window w = a.getWindow();
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            android.view.Display d = a.getWindowManager().getDefaultDisplay();
            android.view.Display.Mode best = null; android.view.Display.Mode cur = d.getMode();
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                if (m.getRefreshRate() + 0.5f < f) continue;
                if (best == null || m.getRefreshRate() < best.getRefreshRate()) best = m;
            }
            if (best == null) for (android.view.Display.Mode m : d.getSupportedModes()) if (best == null || m.getRefreshRate() > best.getRefreshRate()) best = m;
            if (best != null) { lp.preferredDisplayModeId = best.getModeId(); lp.preferredRefreshRate = best.getRefreshRate(); w.setAttributes(lp); }
        } catch (Throwable ignored) {} });
    }

    static void runTweaks(String why) {
        int n = 0;
        if (fps == 0) loadFps();
        applyFps();
        for (String c : TWEAKS) if (exec(c)) n++;
        tweakAt = System.currentTimeMillis();
        tweakStatus = why + "：已发送 " + n + " 条（等待时间5秒/门/人机）";
    }

    static void tickTweaks(long now) {
        if (engineSeenAt > 0 && tweakDone == 0 && now - engineSeenAt > 10000) { tweakDone = 1; runTweaks("启动时"); }
        if (browseAt > tweakAt && now - browseAt > 1500) runTweaks("进图时");
        if (fpsAt > 0 && now - fpsAt > 800) { fpsAt = 0; applyFps(); }
        if (brAt > 0 && now - brAt > 3000) { brAt = 0; Patches.shortenWait("本局"); }
    }

    static volatile String circle = null; // "x y"

    static final long bootAt = System.currentTimeMillis();

    static void line(String l) {
        if (engineSeenAt == 0 && l.contains("LogLoad: Took")) { engineSeenAt = System.currentTimeMillis(); LogCap.note("引擎就绪，10秒后发送设置"); }
        int c = l.indexOf("BlueCircle: ");
        if (c >= 0) { try {
            String[] a = l.substring(c + 12).split(",");
            circle = Math.round(Double.parseDouble(a[2].trim())) + " " + Math.round(Double.parseDouble(a[3].trim()));
        } catch (Throwable ignored) {} }
        if (l.contains("ApplyFrameRateOption")) fpsAt = System.currentTimeMillis();
        if (l.contains("NZBRGameState.cpp:304")) brAt = System.currentTimeMillis();
        int i = l.indexOf("LogNet: Browse: ");
        if (i >= 0) { synchronized (serverLevels) { serverLevels.clear(); } circle = null; browseAt = System.currentTimeMillis(); layerStatus = "正在进图…"; return; }
        i = l.indexOf("StreamingServerLayer Add ");
        if (i >= 0) {
            String p = l.substring(i + "StreamingServerLayer Add ".length()).trim();
            if (p.startsWith("other ")) p = p.substring(6).trim();
            if (p.startsWith("/Game/")) {
                synchronized (serverLevels) { serverLevels.add(p); }
                lastAddAt = System.currentTimeMillis();
                layerStatus = "发现服务器关卡 " + serverLevels.size() + " 个，准备加载";
            }
        }
    }

    static File findUeLog(android.content.Context ctx) {
        List<File> all = new ArrayList<>();
        LogCap.findLogs(ctx.getExternalFilesDir(null), all, 0);
        LogCap.findLogs(ctx.getFilesDir(), all, 0);
        LogCap.findLogs(new File("/storage/emulated/0/UE4Game"), all, 0);
        File best = null;
        for (File f : all) if (f.getName().equals("PUBGM.log") || (f.getName().endsWith(".log") && !f.getName().contains("backup") && f.getPath().contains("Logs"))) {
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }
}
