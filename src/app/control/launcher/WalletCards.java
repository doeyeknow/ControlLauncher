package app.control.launcher;

/**
 * Pure-Java card rules for the wallet (v1.7) — no Android types,
 * so the off-device harness exercises the exact shipping code.
 *
 * What lives here: number hygiene (digits, Luhn), network
 * detection, masked/grouped display forms, expiry validation, and
 * the audit-line builder. The audit builder is deliberately the
 * ONLY way wallet events reach the log, and its signature takes
 * nickname + last4 — a full card number cannot be passed to it,
 * so it cannot reach the audit log through this code.
 *
 * What does NOT live here: the number itself. A full PAN is held
 * only transiently (add form → encrypt → store; reveal → decrypt
 * → display → re-mask) and is never logged, never written
 * anywhere unencrypted, and never leaves this phone. CVV is
 * never collected at all — there is no CVV field anywhere in the
 * wallet, by design.
 */
public final class WalletCards {

    public static final String NETWORK_VISA = "Visa";
    public static final String NETWORK_MASTERCARD = "Mastercard";
    public static final String NETWORK_AMEX = "Amex";
    public static final String NETWORK_DISCOVER = "Discover";
    public static final String NETWORK_OTHER = "Other";

    public static final String[] NETWORKS = {
            NETWORK_VISA, NETWORK_MASTERCARD, NETWORK_AMEX,
            NETWORK_DISCOVER, NETWORK_OTHER};

    private WalletCards() {}

    /** Digits only — spaces and dashes (how people type numbers)
     *  are dropped; anything else makes the result fail validation
     *  downstream because it is not all digits. */
    public static String digitsOnly(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (ch == ' ' || ch == '-') { /* skipped */ }
            else sb.append(ch); // kept so validation rejects it
        }
        return sb.toString();
    }

    private static boolean allDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Luhn check. Card numbers are 13–19 digits; anything else
     *  fails before the checksum runs. */
    public static boolean luhnValid(String digits) {
        if (!allDigits(digits)) return false;
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }

    /** Best-effort network from the number's leading digits. The
     *  user can always override it — detection is a convenience,
     *  never a gate. */
    public static String detectNetwork(String digits) {
        if (digits == null || digits.isEmpty()) return NETWORK_OTHER;
        if (digits.startsWith("4")) return NETWORK_VISA;
        if (digits.length() >= 2) {
            int first2 = Integer.parseInt(digits.substring(0, 2));
            if (first2 >= 51 && first2 <= 55) return NETWORK_MASTERCARD;
            if (digits.length() >= 4) {
                int first4 = Integer.parseInt(digits.substring(0, 4));
                if (first4 >= 2221 && first4 <= 2720) return NETWORK_MASTERCARD;
            }
            if (digits.startsWith("34") || digits.startsWith("37")) return NETWORK_AMEX;
        }
        if (digits.startsWith("6")) return NETWORK_DISCOVER;
        return NETWORK_OTHER;
    }

    /** The last 4 digits — the only part of a number that is ever
     *  stored in the clear, shown unmasked, or logged. */
    public static String last4(String digits) {
        if (digits == null || digits.length() < 4) return "";
        return digits.substring(digits.length() - 4);
    }

    /** Masked display form: "•••• 1234". */
    public static String masked(String digits) {
        String l4 = last4(digits);
        return l4.isEmpty() ? "••••" : "•••• " + l4;
    }

    /** Grouped display form for a revealed number: Amex 4-6-5,
     *  everything else groups of 4. */
    public static String grouped(String digits) {
        if (digits == null || digits.isEmpty()) return "";
        String network = detectNetwork(digits);
        StringBuilder sb = new StringBuilder();
        if (NETWORK_AMEX.equals(network) && digits.length() == 15) {
            sb.append(digits, 0, 4).append(' ');
            sb.append(digits, 4, 10).append(' ');
            sb.append(digits, 10, 15);
            return sb.toString();
        }
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 4 == 0) sb.append(' ');
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /** Expiry sanity: a real month, a plausible year, and not in
     *  the past. A card is good through the END of its expiry
     *  month, so the current month is valid. */
    public static boolean expiryValid(int month, int year, int nowYear, int nowMonth) {
        if (month < 1 || month > 12) return false;
        if (year < nowYear || year > nowYear + 30) return false;
        if (year == nowYear && month < nowMonth) return false;
        return true;
    }

    /** "MM/YY" display form for an expiry. */
    public static String expiryDisplay(int month, int year) {
        return (month < 10 ? "0" : "") + month + "/" + (year % 100 < 10 ? "0" : "") + (year % 100);
    }

    /**
     * The audit-log line for a wallet event. The ONLY card facts
     * it can carry are the nickname and the last 4 — there is no
     * parameter for anything more, so a full number cannot reach
     * the audit log through this builder. {@code action} is one
     * of: added, edited, deleted, number shown, pay pressed.
     */
    public static String auditLine(String action, String nickname, String last4) {
        String nick = nickname == null ? "" : nickname.trim();
        String l4 = last4 == null ? "" : last4;
        StringBuilder sb = new StringBuilder("Wallet card ");
        sb.append(action == null ? "" : action);
        if (!nick.isEmpty()) sb.append(": ").append(nick);
        if (!l4.isEmpty()) sb.append(" •••• ").append(l4);
        sb.append(", origin UI");
        return sb.toString();
    }
}
