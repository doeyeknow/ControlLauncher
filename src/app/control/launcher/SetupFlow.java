package app.control.launcher;

/**
 * First-run setup flow decisions, as pure functions so the setup
 * harness can assert them without Android.
 *
 * The steps, in order: NAME, PERMISSIONS, IMPORT, THEME. Every
 * step is skippable; skipping advances exactly like finishing
 * the step. The flow never traps the user: Back moves to the
 * previous step (or leaves setup from step 0), and finishing
 * the theme step — or skipping through it — completes setup.
 *
 * Ordering rule (Adam's brief): a profile imported in the
 * IMPORT step replaces the whole profile, including the name
 * typed in the NAME step — the imported profile's name wins.
 * {@link #effectiveDisplayName} is that rule, stated once.
 */
public final class SetupFlow {

    public static final int STEP_NAME = 0;
    public static final int STEP_PERMISSIONS = 1;
    public static final int STEP_IMPORT = 2;
    public static final int STEP_THEME = 3;
    public static final int STEP_COUNT = 4;

    private SetupFlow() {}

    /** Setup shows exactly once: whenever the completed flag is
     *  not set. Existing installs already carry the flag from the
     *  old first-run screen, so upgrading never re-shows setup. */
    public static boolean shouldShowSetup(boolean setupDone) {
        return !setupDone;
    }

    /** The next step after finishing or skipping {@code step}.
     *  Never runs past the last step: advancing from THEME means
     *  setup is complete. */
    public static int nextStep(int step) {
        if (step < 0) return STEP_NAME;
        if (step >= STEP_THEME) return STEP_THEME;
        return step + 1;
    }

    /** True when advancing from {@code step} completes setup. */
    public static boolean completesSetup(int step) {
        return step >= STEP_THEME;
    }

    /** The previous step for the Back key. Step 0 has no
     *  previous step; the caller leaves setup instead. */
    public static int prevStep(int step) {
        if (step <= STEP_NAME) return STEP_NAME;
        if (step > STEP_THEME) return STEP_THEME;
        return step - 1;
    }

    /** Clamps a restored step (saved pref, process death) into
     *  range so a stale value can never strand the flow. */
    public static int clampStep(int step) {
        if (step < STEP_NAME) return STEP_NAME;
        if (step > STEP_THEME) return STEP_THEME;
        return step;
    }

    /** The name that survives setup: the imported profile's name
     *  when an import happened, otherwise the typed name. An
     *  empty typed name means "no name set" (the profile's
     *  displayName() falls back to "Someone"). */
    public static String effectiveDisplayName(String typedName,
                                              String importedName,
                                              boolean imported) {
        if (imported) return importedName == null ? "" : importedName;
        return typedName == null ? "" : typedName;
    }
}
