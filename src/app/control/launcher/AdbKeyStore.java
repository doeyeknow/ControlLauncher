package app.control.launcher;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.security.KeyFactory;

/**
 * Control's own ADB identity (v1.1, for wireless-debugging self-start
 * of the shell bridge).
 *
 * One RSA-2048 keypair is generated on first use and kept in
 * app-private files (adb-key.pk8 / adb-key.pub / adb-key.cert), so
 * pairing — whenever and however it happens — is one-time: the phone
 * authorizes THIS key. The TLS client certificate is a minimal
 * self-signed X.509 certificate wrapping the key, DER-encoded by hand
 * because Android gives apps no certificate-minting API; the ADB
 * protocol only needs the public key inside it.
 *
 * Server-side trust: the phone's connect service presents its own
 * self-signed certificate. There is no CA to check it against — the
 * security of ADB-over-TLS is that the phone only accepts client keys
 * it has authorized, and only offers the connect service while
 * wireless debugging is on. We therefore accept the peer certificate
 * and record its fingerprint for display.
 */
public final class AdbKeyStore {

    private static final String F_PRIV = "adb-key.pk8";
    private static final String F_PUB = "adb-key.pub";
    private static final String F_CERT = "adb-key.cert";

    private static KeyPair cached;
    private static Certificate cachedCert;

    private AdbKeyStore() {}

    public static synchronized KeyPair keyPair(Context c) throws Exception {
        if (cached != null) return cached;
        File priv = new File(c.getFilesDir(), F_PRIV);
        File pub = new File(c.getFilesDir(), F_PUB);
        if (priv.exists() && pub.exists()) {
            try {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey pk = kf.generatePrivate(
                        new PKCS8EncodedKeySpec(readAll(priv)));
                PublicKey pu = kf.generatePublic(
                        new X509EncodedKeySpec(readAll(pub)));
                cached = new KeyPair(pu, pk);
                return cached;
            } catch (Exception e) {
                // fall through and mint a fresh identity
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        cached = g.generateKeyPair();
        writeAll(priv, cached.getPrivate().getEncoded());
        writeAll(pub, cached.getPublic().getEncoded());
        // A fresh key invalidates any stored certificate.
        new File(c.getFilesDir(), F_CERT).delete();
        cachedCert = null;
        return cached;
    }

    public static synchronized Certificate certificate(Context c) throws Exception {
        if (cachedCert != null) return cachedCert;
        File f = new File(c.getFilesDir(), F_CERT);
        if (f.exists()) {
            try {
                cachedCert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(readAll(f)));
                return cachedCert;
            } catch (Exception e) {
                // rebuild below
            }
        }
        KeyPair kp = keyPair(c);
        byte[] der = buildSelfSignedCert(kp);
        writeAll(f, der);
        cachedCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
        return cachedCert;
    }

    /** Fingerprint of the phone's self-signed ADB certificate from
     *  the most recent connection, recorded by AdbTls (which accepts
     *  the peer certificate as-is — see the class doc) for the
     *  status lines. Through v1.3 this was written by the framework
     *  SSLContext's trust manager; that context is gone in v1.4 —
     *  its only caller was AdbClient, which now runs on the shared
     *  Bouncy Castle stack like pairing. */
    static volatile String lastPeerFingerprint = "";

    /** Short fingerprint of Control's own ADB public key, for display. */
    public static String ownFingerprint(Context c) {
        try {
            return fingerprint(keyPair(c).getPublic().getEncoded());
        } catch (Exception e) {
            return "unavailable";
        }
    }

    public static String fingerprint(byte[] der) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8 && i < d.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", d[i]));
        }
        return sb.toString();
    }

    // ---- minimal DER writer for the self-signed certificate ----

    private static byte[] buildSelfSignedCert(KeyPair kp) throws Exception {
        // AlgorithmIdentifier: sha256WithRSAEncryption (1.2.840.113549.1.1.11), NULL params
        byte[] algId = tlv(0x30, concat(
                tlv(0x06, new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7,
                        0x0D, 0x01, 0x01, 0x0B}),
                tlv(0x05, new byte[0])));
        byte[] name = derName("Control ADB");
        byte[] validity = tlv(0x30, concat(
                tlv(0x17, "240101000000Z".getBytes("US-ASCII")),
                tlv(0x17, "450101000000Z".getBytes("US-ASCII"))));
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2})); // [0] EXPLICIT v3
        byte[] serial = tlv(0x02, new byte[]{1});
        byte[] spki = kp.getPublic().getEncoded(); // already DER SubjectPublicKeyInfo
        byte[] tbs = tlv(0x30, concat(version, serial, algId, name, validity, name, spki));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] sigBytes = sig.sign();
        byte[] sigBitString = tlv(0x03, concat(new byte[]{0}, sigBytes));
        return tlv(0x30, concat(tbs, algId, sigBitString));
    }

    /** Name ::= RDNSequence with one CN attribute. */
    private static byte[] derName(String cn) throws Exception {
        byte[] atv = tlv(0x30, concat(
                tlv(0x06, new byte[]{0x55, 0x04, 0x03}), // CN = 2.5.4.3
                tlv(0x0C, cn.getBytes("UTF-8"))));       // UTF8String
        return tlv(0x30, tlv(0x31, atv));                // SEQUENCE OF SET OF
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len < 0x100) {
            out.write(0x81);
            out.write(len);
        } else {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }

    private static byte[] readAll(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void writeAll(File f, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
