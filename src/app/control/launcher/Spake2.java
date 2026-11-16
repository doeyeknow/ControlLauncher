package app.control.launcher;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * BoringSSL's "spake25519" SPAKE2, as used by AOSP's ADB pairing
 * (packages/modules/adb/pairing_auth on top of
 * external/boringssl/src/crypto/curve25519/spake25519.cc).
 *
 * This is NOT the RFC 9382 SPAKE2 and NOT a P-256 variant: it is
 * SPAKE2 over Edwards25519 with fixed mask points M (alice) and
 * N (bob). The transcript and scalar handling below transcribe
 * spake25519.cc literally — including its "password scalar hack":
 * after sc_reduce, multiples of the group order are added so the
 * scalar's low three bits clear (bit tests read the running value,
 * exactly like the C code).
 *
 * Roles: the ADB pairing CLIENT is alice; the phone (adbd) is bob.
 * The role flag exists so off-device tests can run the bob side
 * against an independent Python implementation of the same spec.
 */
final class Spake2 {

    // Names include the trailing NUL: AOSP passes sizeof(kClientName).
    private static final byte[] CLIENT_NAME =
            "adb pair client\0".getBytes(StandardCharsets.US_ASCII); // 16 bytes
    private static final byte[] SERVER_NAME =
            "adb pair server\0".getBytes(StandardCharsets.US_ASCII); // 16 bytes

    private final boolean alice;
    private final BigInteger privateKey;
    private final BigInteger passwordScalar;
    private final byte[] passwordHash;
    private final byte[] myMsg;
    private boolean processed;

    Spake2(boolean alice, byte[] password, SecureRandom rng) throws Exception {
        this.alice = alice;
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        passwordHash = sha512.digest(password);

        // Ephemeral key: 64 random bytes, sc_reduce, then x8
        // (BoringSSL left_shift_3 — multiply by the cofactor).
        byte[] tmp = new byte[64];
        rng.nextBytes(tmp);
        BigInteger priv = Ed25519.leToBig(tmp).mod(Ed25519.L);
        privateKey = priv.shiftLeft(3);

        // Password scalar: sc_reduce(SHA512(password)), then the hack —
        // for i = 0,1,2: if bit i of the CURRENT scalar is set, add
        // l << i. Net effect: scalar = s (mod l) with low 3 bits clear.
        BigInteger s = Ed25519.leToBig(passwordHash).mod(Ed25519.L);
        for (int i = 0; i < 3; i++) {
            if (s.testBit(i)) {
                s = s.add(Ed25519.L.shiftLeft(i));
            }
        }
        passwordScalar = s;

        // my_msg = private*B + passwordScalar * (alice ? M : N)
        Ed25519.Point pub = Ed25519.scalarmult(Ed25519.BASE, privateKey);
        Ed25519.Point mask = Ed25519.scalarmult(alice ? Ed25519.M : Ed25519.N, passwordScalar);
        myMsg = Ed25519.encode(Ed25519.add(pub, mask));
    }

    byte[] msg() {
        return myMsg.clone();
    }

    /**
     * Process the peer's 32-byte message and derive the 64-byte
     * shared key material. Throws if the peer's point does not
     * decode. A wrong password does NOT throw here — it yields a
     * different key, and the failure surfaces when the PeerInfo
     * AES-GCM decryption fails, exactly as in AOSP.
     */
    byte[] processMsg(byte[] theirMsg) throws Exception {
        if (processed) throw new IllegalStateException("SPAKE2 context already used");
        processed = true;
        if (theirMsg == null || theirMsg.length != 32) {
            throw new IllegalArgumentException("SPAKE2 message must be 32 bytes");
        }
        Ed25519.Point qStar = Ed25519.decode(theirMsg);
        Ed25519.Point peersMask = Ed25519.scalarmult(
                alice ? Ed25519.N : Ed25519.M, passwordScalar);
        Ed25519.Point q = Ed25519.add(qStar, Ed25519.negate(peersMask));
        Ed25519.Point dh = Ed25519.scalarmult(q, privateKey);
        byte[] dhEncoded = Ed25519.encode(dh);

        // Key = SHA512 over length-prefixed (uint64 LE) fields, in
        // canonical client-first order regardless of role.
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        updateLp(sha512, CLIENT_NAME);
        updateLp(sha512, SERVER_NAME);
        updateLp(sha512, alice ? myMsg : theirMsg);
        updateLp(sha512, alice ? theirMsg : myMsg);
        updateLp(sha512, dhEncoded);
        updateLp(sha512, passwordHash);
        return sha512.digest();
    }

    private static void updateLp(MessageDigest md, byte[] data) {
        byte[] len = new byte[8];
        long l = data.length;
        for (int i = 0; i < 8; i++) {
            len[i] = (byte) (l & 0xff);
            l >>= 8;
        }
        md.update(len);
        md.update(data);
    }

    /** Concatenation helper (password = code bytes || exported key material). */
    static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(a.length + b.length);
        out.write(a, 0, a.length);
        out.write(b, 0, b.length);
        return out.toByteArray();
    }
}
