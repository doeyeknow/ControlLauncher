package app.control.launcher;

import java.math.BigInteger;

/**
 * Edwards25519 point arithmetic, just enough for BoringSSL's
 * "spake25519" SPAKE2 variant (used by AOSP's ADB wireless pairing).
 *
 * Pure BigInteger math on the twisted Edwards curve
 *   -x^2 + y^2 = 1 + d x^2 y^2  (mod p = 2^255 - 19)
 * with the complete (unified) addition formula for a = -1, so the
 * same code adds and doubles. Encoding is the standard Ed25519
 * encoding: y in 32 little-endian bytes, low bit of x in the top bit.
 *
 * No Android dependencies — this class is exercised off-device by the
 * JVM test harness (M/N encoding checks against the coordinates
 * published in BoringSSL's spake25519.cc, RFC 8032 base-point
 * vectors, and a cross-implementation SPAKE2 run against an
 * independent Python implementation).
 */
final class Ed25519 {

    static final BigInteger P =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** d = -121665/121666 mod p. */
    static final BigInteger D = new BigInteger(
            "37095705934669439343138083508754565189542113879843219016388785533085940283555");
    /** 2^((p-1)/4) mod p — sqrt(-1), used by the p = 5 (mod 8) sqrt. */
    static final BigInteger SQRT_M1 = new BigInteger(
            "19681161376707505956807079304988542015446066515923890162744021073123829784752");
    /** Group order l = 2^252 + 27742317777372353535851937790883648493. */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));

    static final class Point {
        final BigInteger x;
        final BigInteger y;

        Point(BigInteger x, BigInteger y) {
            this.x = x;
            this.y = y;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Point)) return false;
            Point q = (Point) o;
            return x.equals(q.x) && y.equals(q.y);
        }

        @Override public int hashCode() {
            return x.hashCode() * 31 + y.hashCode();
        }
    }

    static final Point IDENTITY = new Point(BigInteger.ZERO, BigInteger.ONE);

    static final Point BASE = new Point(
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"));

    /** SPAKE2 mask point M (BoringSSL spake25519.cc, coordinates as
     *  published there; encoding 5ada7e4b…eed12e). */
    static final Point M = new Point(
            new BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
            new BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114"));

    /** SPAKE2 mask point N (encoding 10e3df0a…3bc778). */
    static final Point N = new Point(
            new BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
            new BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000"));

    private Ed25519() {}

    /** Complete addition formula for a = -1 twisted Edwards curves. */
    static Point add(Point a, Point b) {
        BigInteger x1x2 = a.x.multiply(b.x).mod(P);
        BigInteger y1y2 = a.y.multiply(b.y).mod(P);
        BigInteger dt = D.multiply(x1x2).multiply(y1y2).mod(P);
        BigInteger x3 = a.x.multiply(b.y).add(b.x.multiply(a.y)).mod(P)
                .multiply(BigInteger.ONE.add(dt).modInverse(P)).mod(P);
        BigInteger y3 = y1y2.add(x1x2).mod(P)
                .multiply(BigInteger.ONE.subtract(dt).mod(P).modInverse(P)).mod(P);
        return new Point(x3, y3);
    }

    static Point negate(Point a) {
        return new Point(P.subtract(a.x).mod(P), a.y);
    }

    static Point scalarmult(Point pt, BigInteger k) {
        if (k.signum() < 0) throw new IllegalArgumentException("negative scalar");
        Point result = IDENTITY;
        Point addend = pt;
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) result = add(result, addend);
            addend = add(addend, addend);
        }
        return result;
    }

    static byte[] encode(Point pt) {
        byte[] out = bigToLe(pt.y, 32);
        if (pt.x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    /**
     * Decode a 32-byte encoded point (BoringSSL's
     * x25519_ge_frombytes_vartime semantics): recover x from y with
     * the p = 5 (mod 8) square-root algorithm; reject when there is
     * no square root (point not on the curve).
     */
    static Point decode(byte[] s) {
        if (s == null || s.length != 32) {
            throw new IllegalArgumentException("encoded point must be 32 bytes");
        }
        int sign = (s[31] >> 7) & 1;
        byte[] yBytes = s.clone();
        yBytes[31] &= 0x7f;
        BigInteger y = leToBig(yBytes).mod(P);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);          // y^2 - 1
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);   // d*y^2 + 1
        // x = (u v^3) * (u v^7)^((p-5)/8)  (mod p)
        BigInteger v2 = v.multiply(v).mod(P);
        BigInteger v3 = v2.multiply(v).mod(P);
        BigInteger v7 = v3.multiply(v2).multiply(v2).mod(P);
        BigInteger exp = P.subtract(BigInteger.valueOf(5)).shiftRight(3);
        BigInteger x = u.multiply(v3).mod(P)
                .multiply(u.multiply(v7).mod(P).modPow(exp, P)).mod(P);
        BigInteger vx2 = v.multiply(x).multiply(x).mod(P);
        if (vx2.equals(u)) {
            // good root
        } else if (vx2.equals(P.subtract(u).mod(P))) {
            x = x.multiply(SQRT_M1).mod(P);
        } else {
            throw new IllegalArgumentException("point is not on the curve");
        }
        if (x.signum() == 0 && sign == 1) {
            throw new IllegalArgumentException("invalid encoding: x = 0 with sign bit set");
        }
        if ((x.testBit(0) ? 1 : 0) != sign) {
            x = P.subtract(x).mod(P);
        }
        return new Point(x, y);
    }

    /** Little-endian bytes -> non-negative integer. */
    static BigInteger leToBig(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    /** Non-negative integer -> fixed-length little-endian bytes. */
    static byte[] bigToLe(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[len];
        int n = Math.min(be.length, len);
        for (int i = 0; i < n; i++) out[i] = be[be.length - 1 - i];
        return out;
    }
}
