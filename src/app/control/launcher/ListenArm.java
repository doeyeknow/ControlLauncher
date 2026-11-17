package app.control.launcher;

/**
 * The listen-arm state machine (v1.26): pure Java, no clock, no
 * Android. The listen screen owns the timeout clock; this class
 * owns only the rules, so a harness can prove them.
 *
 * States:
 *   IDLE — nothing armed. A tap here does nothing: the tap is a
 *   shortcut for a screen the user already opened, never a way to
 *   arm listening from nowhere.
 *   LISTENING — armed manually (the screen opened) or still
 *   listening after a tap was already confirmed. Audio packets
 *   are decoded in this state.
 *   TAP_CONFIRMED — a tap was heard while LISTENING. Still
 *   listening; the state only records that the tap landed, so
 *   the UI can say so honestly.
 *
 * A tap never starts listening by itself, and a timeout or an
 * explicit disarm always returns to IDLE. The state machine
 * never touches audio or NFC; it only answers the question "is
 * this listen armed, and did a tap confirm it?"
 */
public final class ListenArm {

    public static final int IDLE = 0;
    public static final int LISTENING = 1;
    public static final int TAP_CONFIRMED = 2;

    private int state = IDLE;

    public int state() {
        return state;
    }

    /** True when audio packets should be decoded. */
    public boolean isListening() {
        return state == LISTENING || state == TAP_CONFIRMED;
    }

    /** True when a tap confirmed an armed listen. */
    public boolean tapConfirmed() {
        return state == TAP_CONFIRMED;
    }

    /** Arms listening from IDLE. Returns true when the arm took
     *  effect; re-arming an already-armed listen is a no-op that
     *  returns false and never drops a tap confirmation. */
    public boolean arm() {
        if (state != IDLE) return false;
        state = LISTENING;
        return true;
    }

    /** Records an NFC tap. Only confirms when LISTENING; a tap in
     *  IDLE does nothing and returns false, and a tap when
     *  already TAP_CONFIRMED keeps the state and returns true. */
    public boolean tap() {
        if (state == IDLE) return false;
        state = TAP_CONFIRMED;
        return true;
    }

    /** The listen timeout fired: back to IDLE. Returns true when
     *  a listen was actually armed. */
    public boolean timeout() {
        if (state == IDLE) return false;
        state = IDLE;
        return true;
    }

    /** Explicit disarm (screen closed, packet handled): back to
     *  IDLE from any state. */
    public void disarm() {
        state = IDLE;
    }
}
