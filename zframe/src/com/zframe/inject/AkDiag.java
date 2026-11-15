package com.zframe.inject;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import java.io.*;

/**
 * v1.19 声音诊断（不碰游戏内存）：
 * 引擎默认把 LogAkAudio 分类静音了，所以 Wwise 初始化成功/失败都不写日志。
 * 在引擎启动前，往游戏自己的 Saved/Config/Android/Engine.ini 写入 [Core.Log] LogAkAudio=Verbose，
 * 这是 UE4 自带的日志开关，引擎启动时读取。
 * 防闪退：写入时记下标记，引擎就绪后清掉；如果上次写入后引擎没起来，就自动删掉这几行并永久关闭。
 */
public class AkDiag {
    static final String MARK = "; zframe-akdiag";
    static final String[] LINES = {"LogAkAudio=Verbose", "LogAudio=Log"};
    public static volatile String status = "未启用";

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("zframe", 0); }

    static File iniFile() {
        File base = new File("/storage/emulated/0/UE4Game/PUBGM/PUBGM/Saved");
        // 以游戏实际写日志的目录为准（…/Saved/Logs/PUBGM.log）
        java.util.List<File> logs = new java.util.ArrayList<>();
        LogCap.findLogs(new File("/storage/emulated/0/UE4Game"), logs, 0);
        for (File f : logs) if (f.getName().equals("PUBGM.log") && f.getParentFile() != null && f.getParentFile().getName().equals("Logs")) { base = f.getParentFile().getParentFile(); break; }
        return new File(base, "Config/Android/Engine.ini");
    }

    public static void prepare(Context ctx) {
        try {
            SharedPreferences p = sp(ctx);
            File ini = iniFile();
            if (p.getBoolean("akdiag_off", false)) { remove(ini); status = "已自动关闭（上次开启后引擎没起来）"; return; }
            if (p.getLong("akdiag_boot", 0) != 0) {
                // 上次写了诊断行但引擎没就绪 → 可能是它导致的，关掉
                remove(ini);
                p.edit().putBoolean("akdiag_off", true).putLong("akdiag_boot", 0).commit();
                status = "已自动关闭（上次开启后引擎没起来）";
                LogCap.note("声音诊断: 上次启动没到引擎就绪，已删掉 Engine.ini 诊断行并关闭");
                return;
            }
            write(ini);
            p.edit().putLong("akdiag_boot", System.currentTimeMillis()).commit();
            status = "已开启（看日志里的 声音日志 行）";
            LogCap.note("声音诊断: 已写入 " + ini.getPath());
        } catch (Throwable e) {
            status = "写入失败 " + e.getClass().getSimpleName();
            LogCap.note("声音诊断: 写入失败 " + e);
        }
    }

    static void engineUp(Activity a) {
        try { if (a != null) sp(a).edit().putLong("akdiag_boot", 0).apply(); } catch (Throwable ignored) {}
    }

    static String read(File f) throws IOException {
        if (!f.exists()) return "";
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return new String(bo.toByteArray(), "UTF-8");
        }
    }

    static void save(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        try (OutputStream o = new FileOutputStream(f)) { o.write(s.getBytes("UTF-8")); }
    }

    static String strip(String s) {
        StringBuilder sb = new StringBuilder();
        for (String l : s.split("\n", -1)) {
            String t = l.trim();
            boolean ours = t.equals(MARK);
            for (String x : LINES) if (t.equals(x)) ours = true;
            if (!ours) sb.append(l).append('\n');
        }
        String r = sb.toString();
        while (r.endsWith("\n\n")) r = r.substring(0, r.length() - 1);
        // 去掉只剩空的 [Core.Log]
        r = r.replace("[Core.Log]\n\n", "\n");
        if (r.endsWith("[Core.Log]\n")) r = r.substring(0, r.length() - "[Core.Log]\n".length());
        return r;
    }

    static void write(File f) throws IOException {
        String s = strip(read(f));
        StringBuilder add = new StringBuilder(MARK).append('\n');
        for (String x : LINES) add.append(x).append('\n');
        int i = s.indexOf("[Core.Log]");
        if (i >= 0) {
            int e = s.indexOf('\n', i); if (e < 0) { s = s + "\n"; e = s.length() - 1; }
            s = s.substring(0, e + 1) + add + s.substring(e + 1);
        } else {
            if (s.length() > 0 && !s.endsWith("\n")) s += "\n";
            s += (s.length() > 0 ? "\n" : "") + "[Core.Log]\n" + add;
        }
        save(f, s);
    }

    static void remove(File f) {
        try { if (f.exists()) { String s = read(f); String t = strip(s); if (!t.equals(s)) save(f, t); } } catch (Throwable ignored) {}
    }
}
