package com.zframe.inject;

import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/**
 * 只读查找物品编号：在游戏内存里找名字含关键字（如“风衣”）的字符串，
 * 再找指向这些字符串的地方，看旁边有没有 11 位物品编号（300 亿~400 亿之间）。
 * 只读 /proc/self/mem，不写任何东西。
 */
public class ItemFind {
    public static volatile String status = "未查找";
    static volatile boolean running;

    static final long ID_MIN = 10000000000L, ID_MAX = 99999999999L;

    public static void run(final String key, final Runnable done) {
        if (running) return;
        running = true;
        status = "查找中…（约半分钟）";
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try { find(key, out); }
            catch (Throwable e) { out.append("出错 ").append(e); }
            status = out.length() == 0 ? "没找到含“" + key + "”的名字" : out.toString();
            LogCap.note("物品查找[" + key + "]:\n" + status);
            running = false;
            if (done != null) done.run();
        }, "zframe-itemfind").start();
    }

    static byte[] u32(String s) {
        ByteBuffer b = ByteBuffer.allocate(s.length() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < s.length(); i++) b.putInt(s.charAt(i));
        return b.array();
    }

    static void find(String key, StringBuilder out) throws Exception {
        ArrayList<long[]> regs = Patches.rwRegions(false);
        byte[] pat = u32(key);
        byte[] buf = new byte[1 << 20];
        // 1) 找字符串起点
        LinkedHashMap<Long, String> strs = new LinkedHashMap<>();
        try (RandomAccessFile m = new RandomAccessFile("/proc/self/mem", "r")) {
            for (long[] rg : regs) {
                for (long a = rg[0]; a < rg[1] && strs.size() < 300; a += buf.length - 0x200) {
                    int n = (int) Math.min(buf.length, rg[1] - a);
                    try { m.seek(a); m.readFully(buf, 0, n); } catch (Throwable e) { continue; }
                    outer:
                    for (int i = 0; i + pat.length <= n; i += 4) {
                        if (buf[i] != pat[0] || buf[i + 1] != pat[1]) continue;
                        for (int j = 2; j < pat.length; j++) if (buf[i + j] != pat[j]) continue outer;
                        int s = i;
                        while (s >= 4 && s > i - 160) {
                            int c = (buf[s - 4] & 0xff) | (buf[s - 3] & 0xff) << 8 | (buf[s - 2] & 0xff) << 16 | (buf[s - 1] & 0xff) << 24;
                            if (c < 0x20 || c > 0xffff) break;
                            s -= 4;
                        }
                        StringBuilder sb = new StringBuilder();
                        for (int k = s; k + 4 <= n && sb.length() < 40; k += 4) {
                            int c = (buf[k] & 0xff) | (buf[k + 1] & 0xff) << 8 | (buf[k + 2] & 0xff) << 16 | (buf[k + 3] & 0xff) << 24;
                            if (c < 0x20 || c > 0xffff) break;
                            sb.append((char) c);
                        }
                        strs.put(a + s, sb.toString());
                    }
                }
            }
            if (strs.isEmpty()) return;
            // 2) 找指向这些字符串的指针，看旁边的编号
            HashMap<Integer, String> ptr = new HashMap<>();
            int pmin = Integer.MAX_VALUE, pmax = Integer.MIN_VALUE;
            for (Map.Entry<Long, String> e : strs.entrySet()) {
                int p = (int) (long) e.getKey();
                ptr.put(p, e.getValue());
                pmin = Math.min(pmin, p); pmax = Math.max(pmax, p);
            }
            long umin = pmin & 0xffffffffL, umax = pmax & 0xffffffffL;
            TreeMap<String, TreeSet<Long>> res = new TreeMap<>();
            int refs = 0;
            for (long[] rg : regs) {
                for (long a = rg[0]; a < rg[1] && refs < 400; a += buf.length - 0x200) {
                    int n = (int) Math.min(buf.length, rg[1] - a);
                    try { m.seek(a); m.readFully(buf, 0, n); } catch (Throwable e) { continue; }
                    ByteBuffer bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0x80; i + 0x80 <= n; i += 4) {
                        long v = bb.getInt(i) & 0xffffffffL;
                        if (v < umin || v > umax) continue;
                        String name = ptr.get((int) v);
                        if (name == null) continue;
                        refs++;
                        TreeSet<Long> ids = res.get(name);
                        if (ids == null) { ids = new TreeSet<>(); res.put(name, ids); }
                        for (int k = i - 0x60; k <= i + 0x60; k += 4) {
                            long id = bb.getLong(k);
                            if (id >= ID_MIN && id <= ID_MAX) ids.add(id);
                        }
                    }
                }
            }
            for (String s : new LinkedHashSet<>(strs.values())) {
                TreeSet<Long> ids = res.get(s);
                out.append(s).append(" → ").append(ids == null || ids.isEmpty() ? "(旁边没编号)" : ids.toString()).append('\n');
            }
        }
    }
}
