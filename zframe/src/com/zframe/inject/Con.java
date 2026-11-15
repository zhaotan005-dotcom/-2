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
        "set NZBattleRoyaleGameMode MaxBotLimit 30",
        "getall NZBRGameState WaitingPlayersCountDownTime",
        "getall NZBRGameState StartMatchCountDownTime",
        "getall NZSceneDoorManager StandaloneUnloadAllDoor",
        "getall NZBattleRoyaleGameMode MaxBotLimit",
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
        // v1.23：帧率交给游戏自己的设置（机型已解锁到最高档），这里不再改
        if (true) return;
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
        tickAudio(now);
    }

    // ===== v1.19 声音修复：经大厅初始化 Wwise 音频管理器 =====
    // 日志显示：单机版开机直接从 StartupMap 跳进海岛，绕过了大厅；
    // UNZFrontEndAudioManager / UNZPlatformAudioHelper（加载 UI/BGM 音频库、管理 Wwise 挂起/唤醒）只在大厅地图里初始化。
    // 做法：第一次进海岛后，如果本次启动还没见过大厅音频初始化，就先 open 大厅，等音频初始化完成后再 open 回同一张地图。
    static final String LOBBY = "/Game/Maps/Erangle_RoleHall/NEW/FrontEndMap_NEW";
    public static volatile boolean audioAuto = true;
    static volatile boolean feAudioSeen; static volatile int bounce; // 0 未做 1 已去大厅 2 已回地图
    static volatile long brLoadedAt, feSeenAt; static volatile String brMap;
    public static volatile String audioFix = "等待进图";

    static void loadAudioPref() {
        Activity a = act; if (a == null) return;
        audioAuto = a.getSharedPreferences("zframe", 0).getBoolean("audio_auto3", true);
    }

    public static void setAudioAuto(boolean on) {
        audioAuto = on;
        Activity a = act;
        if (a != null) a.getSharedPreferences("zframe", 0).edit().putBoolean("audio_auto3", on).apply();
    }

    // ===== v1.22 艾挽菜单：进地图（本次启动第一次进图先经过大厅，让声音正常） =====
    public static final String MAP_ERANGEL = "/Game/Maps/ErangelMobile_Runtime/BR-Erangel_Mobile_Main";
    public static final String MAP_ERANGEL2 = "/Game/Maps/ErangeMobileRenew_Runtime/BR-ErangelRenew_Mobile_Main";
    public static final String MAP_SAVAGE = "/Game/Maps/SavageMobile_4x4_Runtime/BR-Savage_Mobile_Main";
    static volatile String pendingMap;

    /** 返回给用户看的提示 */
    public static String goMap(String m) {
        if (audioAuto && !feAudioSeen && startupLobby && brMap == null) {
            // 开机进大厅还没完成：等大厅声音好了再自动进这张图，不重复开大厅
            pendingMap = m; bounce = 1; brLoadedAt = 0;
            return "大厅还在加载声音，好了自动进图";
        }
        if (audioAuto && !feAudioSeen) {
            pendingMap = m; bounce = 1; brLoadedAt = 0;
            audioFix = "先进大厅加载声音，然后自动进图…";
            LogCap.note("进图: 先进大厅 " + LOBBY + "，然后进 " + m);
            exec("open " + LOBBY);
            return "先进大厅加载声音，几秒后自动进图";
        }
        pendingMap = null;
        LogCap.note("进图: " + m);
        exec("open " + m);
        return "正在进图";
    }

    public static String goLobby() {
        pendingMap = null; if (bounce == 1) bounce = 0;
        LogCap.note("进大厅: " + LOBBY);
        exec("open " + LOBBY);
        return "正在进大厅";
    }

    /** 手动：现在就经大厅重进当前地图 */
    public static boolean audioBounceNow() {
        if (brMap == null) { audioFix = "还没进过地图，先进一局再点"; return false; }
        manualBounce = true; bounce = 0; feAudioSeen = false; feSeenAt = 0; brLoadedAt = System.currentTimeMillis() - 5000;
        return true;
    }

    // v1.23：开机在 StartupMap 上就直接进大厅（只一次），声音在大厅里初始化好，之后从艾挽选图直接进，不再来回跳
    static volatile boolean startupLobby;
    static void tickAudio(long now) {
        if (!startupLobby && audioAuto && !feAudioSeen && bounce == 0 && brMap == null && engineSeenAt > 0 && now - engineSeenAt > 3000 && act != null) {
            startupLobby = true;
            audioFix = "开机先进大厅加载声音";
            LogCap.note("声音: 开机直接进大厅 " + LOBBY);
            exec("open " + LOBBY);
        }
        if (bounce == 0 && brLoadedAt > 0 && !feAudioSeen && now - brLoadedAt > 2500) {
            if (!audioAuto && !manualBounce) { brLoadedAt = 0; audioFix = "自动修复已关闭（大厅音频未初始化）"; return; }
            manualBounce = false;
            bounce = 1; brLoadedAt = 0;
            audioFix = "正在进大厅初始化声音…";
            LogCap.note("声音修复: 本次启动没见过大厅音频初始化，先进大厅 " + LOBBY);
            exec("open " + LOBBY);
        }
        if (bounce == 1 && feAudioSeen && feSeenAt > 0 && now - feSeenAt > 4000) {
            bounce = 2;
            String m = pendingMap != null ? pendingMap : brMap != null ? brMap : MAP_SAVAGE;
            pendingMap = null;
            audioFix = "大厅音频已初始化，正在回到地图";
            LogCap.note("声音修复: 大厅音频已初始化，回到 " + m);
            exec("open " + m);
        }
    }
    static volatile boolean manualBounce;

    static volatile String circle = null; // "x y"

    static final long bootAt = System.currentTimeMillis();

    static void line(String l) {
        if (engineSeenAt == 0 && l.contains("LogLoad: Took")) { engineSeenAt = System.currentTimeMillis(); LogCap.note("引擎就绪，10秒后发送设置"); AkDiag.engineUp(act); loadAudioPref(); }
        if (l.contains("UNZFrontEndAudioManager: init") || l.contains("UNZPlatformAudioHelper::OnInitialize")) {
            if (!feAudioSeen) { feAudioSeen = true; feSeenAt = System.currentTimeMillis(); LogCap.note("声音修复: 看到大厅音频初始化"); }
            if (bounce != 1) audioFix = "大厅音频已初始化";
        }
        int tk = l.indexOf("LogLoad: Took");
        if (tk >= 0) {
            int lm = l.indexOf("LoadMap(", tk);
            if (lm >= 0) {
                String m = l.substring(lm + 8); int e = m.indexOf(')'); if (e > 0) m = m.substring(0, e);
                int q = m.indexOf('?'); if (q > 0) m = m.substring(0, q);
                if (m.startsWith("/Game/Maps/") && !m.contains("RoleHall") && !m.contains("FrontEnd")) {
                    brMap = m;
                    if (bounce == 2) audioFix = feAudioSeen ? "已经过大厅初始化，本局应有声音" : "已回到地图";
                    else if (!feAudioSeen && bounce == 0) brLoadedAt = System.currentTimeMillis();
                }
            }
        }
        if (l.contains("AkAudio") || l.contains("Audiokinetic") || l.contains("Wwise(R)")) LogCap.note("声音日志: " + l.trim());
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
