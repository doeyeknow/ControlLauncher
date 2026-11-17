package app.control.launcher;

import java.util.Arrays;

/**
 * Off-device verification for the v1.26 NFC arm signal: the
 * pure-Java halves (NfcArm APDU rules, ListenArm state machine)
 * exercised exactly as shipped. The Android halves — the HCE
 * service and the reader-mode callback — are thin wrappers over
 * these bytes and these transitions, and need two real phones;
 * the on-device checklist covers them.
 *
 * Legs:
 *   1. AID: 8 bytes, proprietary first byte, "CONTROL" body, and
 *      the hex string the manifest XML carries matches the bytes.
 *   2. SELECT: the built APDU parses back to our AID; malformed
 *      SELECTs (wrong CLA/INS/P1/P2, zero length, truncation)
 *      parse to null; a SELECT for another AID is not ours.
 *   3. RESPONSE: the arm response is the arm payload plus 90 00;
 *      the not-found response is 6A 82; parsing refuses wrong
 *      status words and short responses; only the exact arm
 *      response counts as an arm signal.
 *   4. STATE MACHINE: a tap never arms listening by itself; arm
 *      then tap confirms; re-arm never drops a confirmation;
 *      timeout and disarm always return to IDLE; taps before an
 *      arm leave no trace.
 *
 * Run (from ~/workspace/control-launcher):
 *   javac -encoding UTF-8 --release 17 -sourcepath src \
 *     -d harness/classes harness/NfcArmHarness.java
 *   java -cp harness/classes app.control.launcher.NfcArmHarness
 */
public class NfcArmHarness {

    private static int checks = 0;
    private static int failures = 0;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        } else {
            System.out.println("ok:   " + name);
        }
    }

    public static void main(String[] args) {
        // ---- 1. AID ----
        check(NfcArm.AID.length == 8, "AID is 8 bytes");
        check((NfcArm.AID[0] & 0xFF) == 0xF0, "AID starts in the proprietary range");
        check(new String(NfcArm.AID, 1, 7, java.nio.charset.StandardCharsets.US_ASCII)
                .equals("CONTROL"), "AID body spells CONTROL");
        StringBuilder hex = new StringBuilder();
        for (byte b : NfcArm.AID) hex.append(String.format("%02X", b));
        check(hex.toString().equals(NfcArm.AID_HEX), "AID hex matches the AID bytes");
        check(NfcArm.ARM_PAYLOAD.length == 10, "arm payload is 10 bytes");

        // ---- 2. SELECT ----
        byte[] sel = NfcArm.buildSelectApdu();
        check(sel.length == 14, "SELECT APDU length");
        check(sel[0] == 0x00 && (sel[1] & 0xFF) == 0xA4 && sel[2] == 0x04
                && sel[3] == 0x00 && (sel[4] & 0xFF) == 8 && sel[13] == 0x00,
                "SELECT APDU header (CLA/INS/P1/P2/Lc/Le)");
        check(Arrays.equals(NfcArm.parseSelectApdu(sel), NfcArm.AID),
                "built SELECT parses back to our AID");
        check(NfcArm.isSelectForUs(sel), "built SELECT is for us");
        byte[] other = Arrays.copyOf(sel, sel.length);
        other[5] = (byte) 0xA0; // a different AID's first byte
        check(!NfcArm.isSelectForUs(other), "SELECT for another AID is not ours");
        check(NfcArm.parseSelectApdu(null) == null, "null APDU rejected");
        check(NfcArm.parseSelectApdu(new byte[]{0x00, (byte) 0xA4}) == null,
                "short APDU rejected");
        byte[] badCla = Arrays.copyOf(sel, sel.length);
        badCla[0] = 0x01;
        check(NfcArm.parseSelectApdu(badCla) == null, "wrong CLA rejected");
        byte[] badIns = Arrays.copyOf(sel, sel.length);
        badIns[1] = (byte) 0xB0;
        check(NfcArm.parseSelectApdu(badIns) == null, "wrong INS rejected");
        byte[] badP1 = Arrays.copyOf(sel, sel.length);
        badP1[2] = 0x00;
        check(NfcArm.parseSelectApdu(badP1) == null, "wrong P1 rejected");
        byte[] zeroLc = Arrays.copyOf(sel, sel.length);
        zeroLc[4] = 0x00;
        check(NfcArm.parseSelectApdu(zeroLc) == null, "zero-length AID rejected");
        byte[] truncated = Arrays.copyOf(sel, 10);
        check(NfcArm.parseSelectApdu(truncated) == null, "truncated SELECT rejected");

        // ---- 3. RESPONSE ----
        byte[] resp = NfcArm.buildArmResponse();
        check(resp.length == NfcArm.ARM_PAYLOAD.length + 2, "arm response length");
        check((resp[resp.length - 2] & 0xFF) == 0x90 && resp[resp.length - 1] == 0x00,
                "arm response ends 90 00");
        check(Arrays.equals(NfcArm.parseResponse(resp), NfcArm.ARM_PAYLOAD),
                "arm response parses to the arm payload");
        check(NfcArm.isArmResponse(resp), "arm response counts as an arm signal");
        byte[] wrongPayload = Arrays.copyOf(resp, resp.length);
        wrongPayload[0] = (byte) 'X';
        check(!NfcArm.isArmResponse(wrongPayload), "wrong payload is not an arm signal");
        byte[] notFound = NfcArm.buildNotFoundResponse();
        check(notFound.length == 2 && (notFound[0] & 0xFF) == 0x6A
                && (notFound[1] & 0xFF) == 0x82, "not-found response is 6A 82");
        check(NfcArm.parseResponse(notFound) == null, "not-found parses to null");
        check(!NfcArm.isArmResponse(notFound), "not-found is not an arm signal");
        check(NfcArm.parseResponse(null) == null, "null response rejected");
        check(NfcArm.parseResponse(new byte[]{(byte) 0x90}) == null,
                "short response rejected");

        // ---- 4. STATE MACHINE ----
        ListenArm arm = new ListenArm();
        check(arm.state() == ListenArm.IDLE && !arm.isListening()
                && !arm.tapConfirmed(), "starts IDLE");
        check(!arm.tap() && arm.state() == ListenArm.IDLE,
                "a tap never arms listening by itself");
        check(arm.arm() && arm.state() == ListenArm.LISTENING
                && arm.isListening(), "arm moves IDLE to LISTENING");
        check(!arm.arm() && arm.state() == ListenArm.LISTENING,
                "re-arm is a no-op");
        check(arm.tap() && arm.state() == ListenArm.TAP_CONFIRMED
                && arm.isListening() && arm.tapConfirmed(),
                "tap while listening confirms");
        check(arm.tap() && arm.state() == ListenArm.TAP_CONFIRMED,
                "second tap keeps the confirmation");
        check(!arm.arm() && arm.state() == ListenArm.TAP_CONFIRMED,
                "re-arm never drops a confirmation");
        check(arm.timeout() && arm.state() == ListenArm.IDLE,
                "timeout returns to IDLE");
        check(!arm.timeout() && arm.state() == ListenArm.IDLE,
                "timeout in IDLE is a no-op");
        arm.arm();
        arm.disarm();
        check(arm.state() == ListenArm.IDLE, "disarm returns to IDLE");
        check(!arm.tap(), "tap after disarm does nothing");
        // Taps before an arm leave no trace.
        ListenArm fresh = new ListenArm();
        fresh.tap();
        fresh.tap();
        fresh.arm();
        check(fresh.state() == ListenArm.LISTENING && !fresh.tapConfirmed(),
                "taps before arming leave no trace");
        // Full cycle: arm, tap, timeout, arm, tap, disarm.
        ListenArm cycle = new ListenArm();
        boolean cycleOk = cycle.arm() && cycle.tap() && cycle.timeout()
                && cycle.arm() && cycle.tap();
        cycle.disarm();
        check(cycleOk && cycle.state() == ListenArm.IDLE, "full arm cycle");

        System.out.println("---");
        System.out.println(checks + " checks, " + failures + " failures");
        if (failures > 0) System.exit(1);
    }
}
