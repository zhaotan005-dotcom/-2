package com.zframe;

import android.content.Context;

import com.android.apksig.ApkSigner;
import com.zframe.axml.Axml;

import java.io.*;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/** 免 root 注入：把修改器 dex 和启动入口注入游戏 APK，再重新签名。 */
public class Patcher {
    public interface Log { void log(String s); }

    public static final String SHARED_UID = "com.zframe.shared";
    static final int RES_SHARED_UID = 0x0101000b, RES_DEBUGGABLE = 0x0101000f;
    static final int RES_NAME = 0x01010003, RES_EXPORTED = 0x01010010, RES_AUTHORITIES = 0x01010018,
            RES_INIT_ORDER = 0x0101001a;

    /** 修补 base.apk：注入 provider + dex，去掉旧签名。返回包名。 */
    public static String patchBase(Context ctx, File in, File outUnsigned, Log log) throws Exception {
        return patchBase(ctx, in, outUnsigned, log, true);
    }

    /** 设置/替换元素上的属性 */
    static void setAttr(Axml.Node n, Axml.Attr a) {
        n.attrs.removeIf(x -> x.resId == a.resId || (a.name.equals(x.name) && a.ns != null && a.ns.equals(x.ns)));
        n.attrs.add(a);
        n.attrs.sort((x, y) -> Integer.compareUnsigned(x.resId == 0 ? -1 : x.resId, y.resId == 0 ? -1 : y.resId));
    }

    /**
     * inject=true：游戏，注入修改器 dex；inject=false：工具（GG 等），只改共享 UID。
     * 两者都加 sharedUserId=com.zframe.shared 且用同一把钥匙签名，于是在同一个 Linux 用户下运行；
     * 游戏另设 debuggable，进程可被同 UID 的工具读写内存。
     */
    public static String patchBase(Context ctx, File in, File outUnsigned, Log log, boolean inject) throws Exception {
        return patchBase(in, outUnsigned, log, inject ? readAll(ctx.getAssets().open("inject.dex")) : null);
    }

    public static String patchBase(File in, File outUnsigned, Log log, byte[] dex) throws Exception {
        boolean inject = dex != null;
        String pkg;
        int maxDex = 0;
        Pattern dexP = Pattern.compile("^classes(\\d*)\\.dex$");
        try (ZipFile zf = new ZipFile(in)) {
            ZipEntry me = zf.getEntry("AndroidManifest.xml");
            if (me == null) throw new IOException("APK 里没有 AndroidManifest.xml（可能是分包，不是 base）");
            byte[] mraw = readAll(zf.getInputStream(me));
            if (inject && (new String(mraw, "UTF-16LE").contains("com.zframe.inject.ZInit")
                    || new String(mraw, "ISO-8859-1").contains("com.zframe.inject.ZInit")))
                throw new IOException("这个游戏已经注入过 ZFrame 了，不用再注入。直接在游戏卡片上点「声音补丁」。");
            Axml x = Axml.parse(mraw);
            int mi = x.findStart("manifest");
            pkg = x.attr(mi, "package");
            log.log("包名：" + pkg);
            int ai = x.findStart("application");
            if (ai < 0) throw new IOException("找不到 <application>");
            setAttr(x.nodes.get(mi), Axml.str("sharedUserId", RES_SHARED_UID, SHARED_UID));
            setAttr(x.nodes.get(ai), Axml.bool("debuggable", RES_DEBUGGABLE, true));
            if (!inject) {
                byte[] manifest = x.write();
                log.log("工具模式：共享 UID " + SHARED_UID + "，重新打包中…");
                copyZip(zf, outUnsigned, manifest, null, null);
                return pkg;
            }
            int ae = x.findEnd(ai);
            int line = x.nodes.get(ai).line;
            List<Axml.Node> add = new ArrayList<>();
            add.add(Axml.start("provider", line,
                    Axml.str("name", RES_NAME, "com.zframe.inject.ZInit"),
                    Axml.bool("exported", RES_EXPORTED, false),
                    Axml.str("authorities", RES_AUTHORITIES, pkg + ".zframe.init"),
                    new Axml.Attr(Axml.ANDROID_NS, "initOrder", RES_INIT_ORDER, Axml.TYPE_INT, 9999, null, null)));
            add.add(Axml.end("provider", line));
            x.nodes.addAll(ae, add);
            // <queries><package android:name="com.zframe"/></queries>，让游戏能读到框架里的插件
            int me2 = x.findEnd(x.findStart("manifest"));
            List<Axml.Node> q = new ArrayList<>();
            q.add(Axml.start("queries", line));
            q.add(Axml.start("package", line, Axml.str("name", RES_NAME, "com.zframe")));
            q.add(Axml.end("package", line));
            q.add(Axml.end("queries", line));
            x.nodes.addAll(me2, q);
            byte[] manifest = x.write();

            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                Matcher m = dexP.matcher(en.nextElement().getName());
                if (m.matches()) maxDex = Math.max(maxDex, m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1)));
            }
            String dexName = "classes" + (maxDex + 1) + ".dex";
            log.log("注入 " + dexName + "，重新打包中（大游戏需要几分钟）…");
            copyZip(zf, outUnsigned, manifest, dexName, dex);
        }
        return pkg;
    }

    /** 复制 zip，去掉旧签名；可替换 manifest、追加 dex。保持 STORED 条目不压缩。 */
    public static void copyZip(ZipFile zf, File out, byte[] manifest, String dexName, byte[] dex) throws IOException {
        byte[] buf = new byte[1 << 16];
        try (ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 16))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")
                        || n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF"))) continue;
                if (manifest != null && n.equals("AndroidManifest.xml")) {
                    put(zo, n, manifest, ZipEntry.DEFLATED);
                    continue;
                }
                ZipEntry ne = new ZipEntry(n);
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(e.getSize());
                    ne.setCompressedSize(e.getSize());
                    ne.setCrc(e.getCrc());
                }
                zo.putNextEntry(ne);
                try (InputStream is = zf.getInputStream(e)) {
                    int r;
                    while ((r = is.read(buf)) > 0) zo.write(buf, 0, r);
                }
                zo.closeEntry();
            }
            if (dex != null) put(zo, dexName, dex, ZipEntry.DEFLATED);
        }
    }

    static void put(ZipOutputStream zo, String name, byte[] data, int method) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 c = new CRC32(); c.update(data);
            e.setSize(data.length); e.setCompressedSize(data.length); e.setCrc(c.getValue());
        }
        zo.putNextEntry(e);
        zo.write(data);
        zo.closeEntry();
    }

    /** 用框架自带的密钥签名（v1+v2+v3），并按 16KB 对齐 .so */
    public static void sign(Context ctx, File in, File out) throws Exception {
        sign(readAll(ctx.getAssets().open("gkey.pk8")), ctx.getAssets().open("gcert.der"), in, out);
    }

    public static void sign(byte[] k, InputStream certIn, File in, File out) throws Exception {
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(k));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(certIn);
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder("zframe", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in).setOutputApk(out)
                .setMinSdkVersion(24)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(true)
                .setAlignmentPreserved(false).setLibraryPageAlignmentBytes(16384)
                .build().sign();
    }

    /** 分包只需去掉旧签名再用同一把钥匙签名 */
    public static void resignSplit(Context ctx, File in, File out) throws Exception {
        File tmp = new File(out.getPath() + ".tmp");
        try (ZipFile zf = new ZipFile(in)) { copyZip(zf, tmp, null, null, null); }
        sign(ctx, tmp, out);
        tmp.delete();
    }

    // ================= 声音补丁（v1.20） =================
    // 游戏的 Wwise 模块（AkAudio）从来没被加载。引擎开机约 3 秒时会在游戏线程里加载一个不存在的模块
    // "SoftwareOcclusionCulling"（只加载一次，返回值只存起来不用）。把这个 UTF-32 名字改成 "AkAudio"，
    // 引擎就会自己把 Wwise 模块加载起来。只改 libUE4.so 里 32 字节，可以随时还原。
    public static final String UE4_LIB = "lib/armeabi-v7a/libUE4.so";
    public static final long SND_OFF = 0x45e7104L;
    public static final byte[] SND_ORIG = u32("SoftwareOcclusionCulling");   // 100 字节（含结尾 0）
    public static final byte[] SND_NEW;
    static {
        byte[] ak = u32("AkAudio");            // 32 字节
        SND_NEW = SND_ORIG.clone();
        System.arraycopy(ak, 0, SND_NEW, 0, ak.length);
    }

    static byte[] u32(String s) {
        byte[] b = new byte[(s.length() + 1) * 4];
        for (int i = 0; i < s.length(); i++) b[i * 4] = (byte) s.charAt(i);
        return b;
    }

    /** 返回 1=原版，2=已打声音补丁，0=对不上（不是这个版本的 libUE4，不能改） */
    public static int soundState(File apk) throws IOException {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry(UE4_LIB);
            if (e == null) return 0;
            byte[] cur = new byte[SND_ORIG.length];
            try (InputStream is = zf.getInputStream(e)) {
                skipFully(is, SND_OFF);
                readFully(is, cur);
            }
            if (Arrays.equals(cur, SND_ORIG)) return 1;
            if (Arrays.equals(cur, SND_NEW)) return 2;
            return 0;
        }
    }

    static void skipFully(InputStream is, long n) throws IOException {
        byte[] b = new byte[1 << 16];
        while (n > 0) { int r = is.read(b, 0, (int) Math.min(b.length, n)); if (r < 0) throw new EOFException(); n -= r; }
    }

    static void readFully(InputStream is, byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) { int r = is.read(b, o, b.length - o); if (r < 0) throw new EOFException(); o += r; }
    }

    /** 把 libUE4.so 从 InputStream 复制到 out，同时把 SND_OFF 处的字节换成 repl */
    static void copyPatched(InputStream is, OutputStream out, byte[] repl, CRC32 crc) throws IOException {
        byte[] b = new byte[1 << 16];
        long pos = 0; int r;
        long ps = SND_OFF, pe = SND_OFF + repl.length;
        while ((r = is.read(b)) > 0) {
            long s = pos, e = pos + r;
            if (e > ps && s < pe) {
                long from = Math.max(s, ps), to = Math.min(e, pe);
                System.arraycopy(repl, (int) (from - ps), b, (int) (from - s), (int) (to - from));
            }
            if (crc != null) crc.update(b, 0, r);
            if (out != null) out.write(b, 0, r);
            pos = e;
        }
    }

    /** 复制已注入的游戏 base.apk，只改 libUE4.so 那 32 字节（toAk=true 打补丁，false 还原），其它原样 */
    public static void patchSound(File in, File outUnsigned, boolean toAk, Log log) throws IOException { patchSound(in, outUnsigned, toAk, null, log); }

    static boolean isOurDex(byte[] d) {
        byte[] k = "Lcom/zframe/inject/ZInit;".getBytes();
        outer:
        for (int i = 0; i + k.length <= d.length; i++) {
            for (int j = 0; j < k.length; j++) if (d[i + j] != k[j]) continue outer;
            return true;
        }
        return false;
    }

    /** newDex 不为空时，顺便把游戏里注入的 ZFrame dex 换成新的（游戏内置内核也更新） */
    public static void patchSound(File in, File outUnsigned, boolean toAk, byte[] newDex, Log log) throws IOException {
        byte[] want = toAk ? SND_NEW : SND_ORIG;
        byte[] buf = new byte[1 << 16];
        try (ZipFile zf = new ZipFile(in);
             ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outUnsigned), 1 << 16))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory()) continue;
                if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA")
                        || n.endsWith(".EC") || n.equals("META-INF/MANIFEST.MF"))) continue;
                if (newDex != null && n.matches("^classes\\d*\\.dex$")) {
                    byte[] d = readAll(zf.getInputStream(e));
                    if (isOurDex(d)) {
                        log.log("更新游戏内置内核 " + n);
                        ZipEntry de = new ZipEntry(n);
                        de.setMethod(ZipEntry.DEFLATED);
                        zo.putNextEntry(de); zo.write(newDex); zo.closeEntry();
                        continue;
                    }
                }
                ZipEntry ne = new ZipEntry(n);
                boolean lib = n.equals(UE4_LIB);
                if (e.getMethod() == ZipEntry.STORED) {
                    ne.setMethod(ZipEntry.STORED);
                    ne.setSize(e.getSize());
                    ne.setCompressedSize(e.getSize());
                    if (lib) {
                        CRC32 c = new CRC32();
                        try (InputStream is = zf.getInputStream(e)) { copyPatched(is, null, want, c); }
                        ne.setCrc(c.getValue());
                    } else ne.setCrc(e.getCrc());
                }
                if (lib) log.log("改 libUE4.so（" + (e.getSize() >> 20) + " MB）…");
                zo.putNextEntry(ne);
                try (InputStream is = zf.getInputStream(e)) {
                    if (lib) copyPatched(is, zo, want, null);
                    else { int r; while ((r = is.read(buf)) > 0) zo.write(buf, 0, r); }
                }
                zo.closeEntry();
            }
        }
    }

    /** 已安装的游戏是不是用 ZFrame 的钥匙签的（是才能覆盖安装，不用卸载） */
    public static boolean signedByUs(Context ctx, String pkg) {
        try {
            byte[] mine = readAll(ctx.getAssets().open("gcert.der"));
            android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg,
                    android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES);
            android.content.pm.Signature[] sg = pi.signingInfo.hasMultipleSigners()
                    ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
            for (android.content.pm.Signature s : sg) if (Arrays.equals(s.toByteArray(), mine)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16]; int r;
            while ((r = i.read(b)) > 0) bo.write(b, 0, r);
            return bo.toByteArray();
        }
    }
}
