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
            Axml x = Axml.parse(readAll(zf.getInputStream(me)));
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

    public static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[1 << 16]; int r;
            while ((r = i.read(b)) > 0) bo.write(b, 0, r);
            return bo.toByteArray();
        }
    }
}
