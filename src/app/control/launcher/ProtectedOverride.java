package app.control.launcher;

import java.util.HashMap;
import java.util.Map;

/**
 * The protected-package override (v1.36). Pure Java — no Android
 * classes — so the harness exercises the exact shipping
 * decisions, wording, and scope.
 *
 * Adam's order (2026-11-16): "I want to be able to disable the
 * system critical packages." The refuse-lists are NOT removed:
 * they stay the default, and the override is a switch the user
 * turns on deliberately, with a strong confirmation, after
 * which disable/enable — and ONLY disable/enable — is permitted
 * on refuse-listed packages, one strong confirmation per action.
 *
 * The rule lives here so the bridge and the client decide from
 * one source of truth:
 *
 *   - Control itself (app.control.launcher) is refused ALWAYS,
 *     override or not: disabling it removes the only tool that
 *     can re-enable it. That is a lockout, not a feature.
 *   - A refuse-listed package (either list) is refused unless
 *     the override is on.
 *   - The override applies to SET_APP_ENABLED only. Every other
 *     op keeps the existing refusals unchanged — force-stop,
 *     clear-data, uninstall, and suspend never consult the
 *     override, and the bridge reads the "override" field in
 *     exactly one place (the SET_APP_ENABLED case). An old
 *     bridge (v6 and below) never reads the field at all, so it
 *     behaves exactly as override=false: it refuses. That is
 *     the fail-closed property, and the harness pins it.
 *   - A null or empty package refuses: fail closed. (The
 *     bridge validates the package pattern first, so this is
 *     unreachable in production — it is a safety net.)
 *
 * Consequences are named per package where the consequence is
 * known, so the per-action confirmation says what will
 * actually break instead of a generic warning. Every member of
 * both refuse-lists has an entry; the fallback covers any
 * future list member.
 */
public final class ProtectedOverride {

    /** The one package the override can never touch. */
    public static final String OWN_PACKAGE = "app.control.launcher";

    /** The only op the override ever permits. */
    public static final String OVERRIDE_OP = "SET_APP_ENABLED";

    /** The audit-log parameters fragment marking an override
     *  action, so the log shows the override was in play. */
    public static final String AUDIT_MARKER = "override=protected-disable";

    /** The governance answer for one enable/disable attempt. */
    public enum Decision {
        /** The attempt may run. */
        ALLOW,
        /** Refuse-listed and the override is off. */
        REFUSE_PROTECTED,
        /** Control itself: refused always, override or not. */
        REFUSE_LOCKOUT
    }

    private static final Map<String, String> CONSEQUENCES = new HashMap<>();

    static {
        CONSEQUENCES.put("android",
                "the Android system package itself — the phone may not boot or run until it is re-enabled");
        CONSEQUENCES.put("com.android.systemui",
                "the status bar, notifications, and navigation buttons disappear until it is re-enabled");
        CONSEQUENCES.put("com.android.settings",
                "the Settings app disappears until it is re-enabled");
        CONSEQUENCES.put("com.android.shell",
                "the shell the bridge runs on stops — the bridge itself may stop working until it is re-enabled from a computer");
        CONSEQUENCES.put("com.android.phone",
                "phone calls stop working until it is re-enabled");
        CONSEQUENCES.put("com.android.server.telecom",
                "phone calls stop working until it is re-enabled");
        CONSEQUENCES.put("com.android.packageinstaller",
                "installing apps through the system installer stops working until it is re-enabled");
        CONSEQUENCES.put("com.google.android.packageinstaller",
                "installing apps through the system installer stops working until it is re-enabled");
        CONSEQUENCES.put("com.android.permissioncontroller",
                "permission prompts stop working until it is re-enabled");
        CONSEQUENCES.put("com.google.android.permissioncontroller",
                "permission prompts stop working until it is re-enabled");
        CONSEQUENCES.put("com.google.android.gms",
                "Google Play services stops — apps that depend on it, including the Play Store and push notifications, break until it is re-enabled");
        CONSEQUENCES.put("com.google.android.gsf",
                "the Google services framework stops — Google sign-in and sync break until it is re-enabled");
        CONSEQUENCES.put("com.android.vending",
                "the Play Store disappears until it is re-enabled");
        CONSEQUENCES.put("com.android.providers.settings",
                "system settings storage stops — apps and the system itself misbehave until it is re-enabled");
        CONSEQUENCES.put("com.android.providers.telephony",
                "carrier and messaging storage stops — calls and texts may break until it is re-enabled");
        CONSEQUENCES.put("com.android.providers.media",
                "the media library stops — photos, music, and files may vanish from apps until it is re-enabled");
        CONSEQUENCES.put("com.android.providers.downloads",
                "the download manager stops — downloads stall until it is re-enabled");
        CONSEQUENCES.put("com.android.bluetooth",
                "Bluetooth stops working until it is re-enabled");
        CONSEQUENCES.put("com.android.nfc",
                "NFC stops working until it is re-enabled");
        CONSEQUENCES.put("com.android.networkstack",
                "networking stops working until it is re-enabled");
        CONSEQUENCES.put("com.android.networkstack.tethering",
                "networking and tethering stop working until it is re-enabled");
        CONSEQUENCES.put("com.google.android.networkstack",
                "networking stops working until it is re-enabled");
        CONSEQUENCES.put("com.android.wifi.resources",
                "Wi-Fi stops working until it is re-enabled");
        CONSEQUENCES.put("com.google.android.wifi.resources",
                "Wi-Fi stops working until it is re-enabled");
        CONSEQUENCES.put("com.android.ons",
                "carrier network services stop until it is re-enabled");
        CONSEQUENCES.put("com.android.stk",
                "SIM toolkit services stop until it is re-enabled");
        CONSEQUENCES.put("com.google.android.euicc",
                "eSIM management stops until it is re-enabled");
        CONSEQUENCES.put("com.android.providers.blockednumber",
                "call blocking stops until it is re-enabled");
        CONSEQUENCES.put("com.android.providers.userdictionary",
                "the user dictionary stops until it is re-enabled");
    }

    private ProtectedOverride() {}

    /** Whether the package is on either refuse-list. */
    public static boolean isProtected(String pkg) {
        if (pkg == null) return false;
        return ProcessRules.REFUSE_ALL.contains(pkg)
                || ProcessRules.REFUSE_DESTRUCTIVE.contains(pkg);
    }

    /** Whether the package is Control itself. */
    public static boolean isOwnPackage(String pkg) {
        return OWN_PACKAGE.equals(pkg);
    }

    /**
     * The governance answer for one SET_APP_ENABLED attempt.
     * {@code override} is the switch state the client sent (an
     * old bridge never sends it, so it reads false there — the
     * fail-closed case). Direction does not matter: disabling
     * and enabling a protected package take the same gate, and
     * Control itself is refused in both directions.
     */
    public static Decision decideEnable(String pkg, boolean override) {
        if (pkg == null || pkg.isEmpty()) return Decision.REFUSE_PROTECTED;
        if (OWN_PACKAGE.equals(pkg)) return Decision.REFUSE_LOCKOUT;
        if (isProtected(pkg) && !override) return Decision.REFUSE_PROTECTED;
        return Decision.ALLOW;
    }

    /**
     * The refusal text for a refused decision. The protected
     * wordings are exactly the bridge's long-standing refusal
     * strings, so the non-override path reads exactly as it
     * always has. ALLOW has no refusal text (empty string).
     */
    public static String refusalText(String pkg, Decision decision) {
        if (decision == Decision.REFUSE_LOCKOUT) return lockoutReason();
        if (decision == Decision.REFUSE_PROTECTED) {
            if (pkg != null && ProcessRules.REFUSE_ALL.contains(pkg)) {
                return "Refused: " + pkg + " is on the bridge refuse-list.";
            }
            return "Refused: destructive control of " + pkg
                    + " is protected by the bridge refuse-list.";
        }
        return "";
    }

    /** Why Control itself can never be disabled. */
    public static String lockoutReason() {
        return "Refused: disabling Control itself would remove the only tool "
                + "that can re-enable it. Control can never be disabled from here.";
    }

    /**
     * What disabling this package does, in plain words. Every
     * refuse-listed package has a specific entry; anything else
     * gets the generic fallback (a future list member is never
     * left without a warning).
     */
    public static String consequenceFor(String pkg) {
        String specific = CONSEQUENCES.get(pkg);
        if (specific != null) return specific;
        return "a system-critical package — parts of the phone may stop "
                + "working until it is re-enabled";
    }

    /** The recovery path named in every protected confirmation:
     *  the exact adb re-enable command. Kept to one line (v1.38):
     *  the modal carries the package, the consequence, and this
     *  command, and nothing else. */
    public static String recoveryText(String pkg) {
        return "Recovery — re-enable here, or: adb shell pm enable " + pkg;
    }

    /** Whether the client should send override=true for this
     *  package: the switch is on, the package is protected, and
     *  it is not Control itself. Ordinary packages never need
     *  the flag, and Control itself never gets it. */
    public static boolean overrideApplies(String pkg, boolean switchOn) {
        return switchOn && isProtected(pkg) && !OWN_PACKAGE.equals(pkg);
    }

    /** The per-action confirm body for disabling a protected
     *  package under the override: the exact package, the
     *  specific consequence, and the recovery command — three
     *  short lines, no surrounding prose (v1.38). The command
     *  ends the body with no trailing period, so it stays
     *  copy-pasteable. */
    public static String disableConfirmBody(String pkg) {
        return pkg + "\nDisabling means " + consequenceFor(pkg) + ".\n\n"
                + recoveryText(pkg);
    }

    /** The per-action confirm body for enabling a protected
     *  package under the override: same three facts, same
     *  terse shape. */
    public static String enableConfirmBody(String pkg) {
        return pkg + "\nThis re-enables a protected package — it can run again.\n\n"
                + recoveryText(pkg);
    }

    /** Title of the strong confirmation for turning the switch on. */
    public static String switchConfirmTitle() {
        return "Allow disabling protected packages?";
    }

    /** Body of the strong confirmation for turning the switch on. */
    public static String switchConfirmBody() {
        return "Protected packages are the system-critical ones: System UI "
                + "(status bar and navigation), Settings, Phone, and Google "
                + "Play services among them. Disabling one can remove the "
                + "status bar, Settings, phone calls, or Play services until "
                + "it is re-enabled.\n\n"
                + "Recovery is re-enabling the package from this screen "
                + "while the bridge runs, or from a computer with adb. "
                + "Control itself can never be disabled — that stays refused.\n\n"
                + "Every protected disable still asks first, names the "
                + "package and what will break, and is written to the audit log.";
    }

    /** Status line while the switch is on. */
    public static String switchOnText() {
        return "On — strong confirmation each time. Control itself can "
                + "never be disabled.";
    }

    /** Status line while the switch is off. */
    public static String switchOffText() {
        return "Off — protected system packages stay refused.";
    }

    /** The process-menu label for disable/enable: a protected
     *  row names the risk while the override is on. */
    public static String menuLabel(boolean currentlyEnabled, boolean protectedPkg) {
        if (protectedPkg) {
            return currentlyEnabled ? "Disable app — protected"
                    : "Enable app — protected";
        }
        return currentlyEnabled ? "Disable app" : "Enable app";
    }
}
