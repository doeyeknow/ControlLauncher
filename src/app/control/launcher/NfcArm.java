package app.control.launcher;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * The NFC arm signal (v1.26): the tiny payload a tap carries.
 *
 * Tapping two phones back to back puts the receiving phone's
 * listen screen into its confirmed-armed state. The tap carries
 * NO key material and NO mesh data — only this arm signal. All
 * data travels by audio afterwards (see AudioModem). Android Beam
 * is gone, so the mechanism is card emulation on the presenting
 * side (ControlHceService, an HCE service) plus reader mode on
 * the receiving side (AudioKeyListenActivity). The two roles are
 * asymmetric on purpose: reader mode disables card emulation on
 * that device while active, so the sender shows the play screen
 * (HCE presents) and the receiver shows the listen screen
 * (reader mode armed). A tap with no armed listen screen does
 * nothing.
 *
 * This class is pure Java so the APDU rules can be proven by a
 * harness without a device. The Android halves (HCE service,
 * reader-mode callback) are thin wrappers over these bytes.
 */
public final class NfcArm {

    /** Application ID: 0xF0 (proprietary range) + "CONTROL". */
    public static final byte[] AID = {
            (byte) 0xF0, 'C', 'O', 'N', 'T', 'R', 'O', 'L'
    };
    /** The same AID as the hex string the apduservice XML needs. */
    public static final String AID_HEX = "F0434F4E54524F4C";

    /** The arm payload: the only bytes a tap delivers. */
    public static final byte[] ARM_PAYLOAD = "CTL-LISTEN".getBytes(StandardCharsets.US_ASCII);

    /** Status word: success. */
    public static final int SW_OK = 0x9000;
    /** Status word: file not found — answered to a SELECT for an
     *  AID that is not ours. */
    public static final int SW_NOT_FOUND = 0x6A82;

    private NfcArm() {}

    /** Builds the SELECT-by-DF-name APDU for our AID:
     *  CLA 00, INS A4, P1 04, P2 00, Lc, AID, Le 00. */
    public static byte[] buildSelectApdu() {
        byte[] apdu = new byte[6 + AID.length];
        apdu[0] = 0x00;
        apdu[1] = (byte) 0xA4;
        apdu[2] = 0x04;
        apdu[3] = 0x00;
        apdu[4] = (byte) AID.length;
        System.arraycopy(AID, 0, apdu, 5, AID.length);
        apdu[5 + AID.length] = 0x00;
        return apdu;
    }

    /** Parses a SELECT APDU and returns the requested AID, or null
     *  when the APDU is not a well-formed SELECT-by-DF-name. */
    public static byte[] parseSelectApdu(byte[] apdu) {
        if (apdu == null || apdu.length < 6) return null;
        if (apdu[0] != 0x00 || (apdu[1] & 0xFF) != 0xA4) return null;
        if (apdu[2] != 0x04 || apdu[3] != 0x00) return null;
        int lc = apdu[4] & 0xFF;
        if (lc == 0 || apdu.length < 5 + lc) return null;
        byte[] aid = new byte[lc];
        System.arraycopy(apdu, 5, aid, 0, lc);
        return aid;
    }

    /** True when the APDU is a SELECT for our AID. */
    public static boolean isSelectForUs(byte[] apdu) {
        byte[] aid = parseSelectApdu(apdu);
        return aid != null && Arrays.equals(aid, AID);
    }

    /** The response to a SELECT for our AID: the arm payload
     *  followed by the success status word. */
    public static byte[] buildArmResponse() {
        byte[] out = new byte[ARM_PAYLOAD.length + 2];
        System.arraycopy(ARM_PAYLOAD, 0, out, 0, ARM_PAYLOAD.length);
        out[out.length - 2] = (byte) 0x90;
        out[out.length - 1] = 0x00;
        return out;
    }

    /** The response to anything that is not a SELECT for our AID. */
    public static byte[] buildNotFoundResponse() {
        return new byte[]{(byte) 0x6A, (byte) 0x82};
    }

    /** Extracts the payload from a response APDU, or null when the
     *  status word is not success or the response is malformed. */
    public static byte[] parseResponse(byte[] response) {
        if (response == null || response.length < 2) return null;
        int sw = ((response[response.length - 2] & 0xFF) << 8)
                | (response[response.length - 1] & 0xFF);
        if (sw != SW_OK) return null;
        byte[] out = new byte[response.length - 2];
        System.arraycopy(response, 0, out, 0, out.length);
        return out;
    }

    /** True when the response is a valid arm signal from our HCE
     *  service: success status + exactly the arm payload. */
    public static boolean isArmResponse(byte[] response) {
        byte[] payload = parseResponse(response);
        return payload != null && Arrays.equals(payload, ARM_PAYLOAD);
    }
}
