package app.control.launcher;

/**
 * Protocol channel-id derivation (PROTOCOL.md §5), from primitives so
 * it stays pure Java and harness-testable.
 *
 * NOTE the deliberate difference from the app's local slot identity
 * (Profile.slotIdentity): the local identity fingerprints the key's
 * BASE64 STRING (SHA-256, first 4 bytes / 8 hex) and keys the mesh /
 * Bluetooth / LAN transports and the on-device thread store. The
 * protocol fingerprint is over the RAW KEY BYTES (SHA-256, first 8
 * bytes / 16 hex). A private channel therefore has two ids — one per
 * world — and this class is the only place relay ids are derived.
 */
public final class RelayIds {

    private RelayIds() {}

    /** Relay channel id for a walkie slot.
     *  @param number    the slot's ORIGINAL number (from wireId when
     *                   the slot was moved by an invite, else its own)
     *  @param isPrivate slot mode
     *  @param keyB64    the slot key, base64 standard (private only) */
    public static String walkieChannelId(int number, boolean isPrivate, String keyB64) {
        if (!isPrivate) return "open:" + number;
        byte[] key = decodeKey(keyB64);
        if (key == null) return null;
        return "priv:" + number + ":" + RelayKeys.fingerprint(key);
    }

    public static String chatChannelId(String keyB64) {
        byte[] key = decodeKey(keyB64);
        return key == null ? null : "chat:" + RelayKeys.fingerprint(key);
    }

    public static String dmChannelId(String keyB64) {
        byte[] key = decodeKey(keyB64);
        return key == null ? null : "dm:" + RelayKeys.fingerprint(key);
    }

    /** The original slot number a slot speaks as: the number inside
     *  its wireId when present ("priv:7:…" / "open:7"), else its own. */
    public static int wireNumber(int ownNumber, String wireId) {
        if (wireId != null && !wireId.isEmpty()) {
            String[] parts = wireId.split(":");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (Exception ignored) {
                }
            }
        }
        return ownNumber;
    }

    /** Raw channel-key bytes from the app's base64-standard storage,
     *  or null when the stored value isn't a 32-byte key. */
    public static byte[] decodeKey(String keyB64) {
        if (keyB64 == null || keyB64.isEmpty()) return null;
        try {
            byte[] key = RelayKeys.b64StdDecode(keyB64.trim());
            return key.length == 32 ? key : null;
        } catch (Exception e) {
            return null;
        }
    }
}
