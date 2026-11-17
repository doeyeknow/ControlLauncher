package app.control.launcher;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

/**
 * The presenting side of the NFC arm signal (v1.26).
 *
 * When another phone's listen screen taps this phone, its reader
 * sends a SELECT for our AID; this service answers with the arm
 * payload and nothing else. It presents whenever the system
 * routes the tap here — no activity needs to be in the
 * foreground — but the tap only means anything to a receiver
 * whose listen screen is armed (see ListenArm). It never sees,
 * stores, or sends key material: the tap is an arm signal, and
 * all data travels by audio.
 *
 * Anything that is not a SELECT for our AID gets the standard
 * "not found" status word, so unrelated readers learn nothing.
 */
public class ControlHceService extends HostApduService {

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras) {
        if (NfcArm.isSelectForUs(commandApdu)) {
            return NfcArm.buildArmResponse();
        }
        return NfcArm.buildNotFoundResponse();
    }

    @Override
    public void onDeactivated(int reason) {
        // Nothing to clean up: the service is stateless.
    }
}
