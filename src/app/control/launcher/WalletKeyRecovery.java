package app.control.launcher;

/**
 * The wallet key-regeneration decision (v1.39). Pure Java — the
 * harness pins the rule; the Android Keystore calls themselves
 * (delete + generate) live in {@link WalletStore} and are proven
 * on-device.
 *
 * THE THEORY (unconfirmed — it is a theory, stated as one): the
 * persisted Keystore key entry itself is unusable on Adam's
 * phone. WalletStore.key() returns the existing entry forever,
 * so a bad entry fails every version identically at cipher init
 * under every provider, which matches everything observed: the
 * AndroidKeyStore lookup is known to throw
 * NoSuchAlgorithmException on his A16, and the default supplier
 * fails too, identically across versions. Saving has never
 * worked on his phone, so the vault is almost certainly empty —
 * but the rule does not assume it.
 *
 * THE RULE — regeneration happens at most once per save
 * attempt, on the SAVE path only, and only when encrypt threw
 * WalletCrypto.AllFailedException (every cipher supplier
 * failed):
 *   - Vault provably empty (0 cards) → regenerate silently and
 *     retry the save once. Nothing sealed exists to orphan.
 *   - Vault holds any cards, or the count is unknown (the list
 *     itself failed) → ask first, naming the consequence: a new
 *     key means previously saved cards stop opening and must be
 *     re-added. Regenerate + retry only on confirmation.
 *   - Already regenerated once this attempt, or the failure was
 *     not an all-cipher failure → give up; the failure stands
 *     and the full text is shown.
 * Never regenerate on the decrypt/reveal path: a reveal failure
 * must never destroy the key the cards were sealed under.
 */
public final class WalletKeyRecovery {

    /** What the save flow should do after a failed save. */
    public enum Action {
        /** Regenerate the key and retry the save once, silently. */
        REGENERATE_SILENTLY,
        /** Ask the user first (see confirm copy below). */
        ASK_BEFORE_REGENERATE,
        /** Do not regenerate; show the failure. */
        GIVE_UP
    }

    private WalletKeyRecovery() {}

    /**
     * The regeneration decision.
     *
     * @param allCiphersFailed true when the save failed with
     *                         WalletCrypto.AllFailedException
     * @param cardCount        cards in the vault, or null when
     *                         the count could not be read
     * @param alreadyRegenerated true when this save attempt
     *                         already regenerated once
     */
    public static Action decide(boolean allCiphersFailed, Integer cardCount,
                                boolean alreadyRegenerated) {
        if (!allCiphersFailed || alreadyRegenerated) return Action.GIVE_UP;
        if (cardCount != null && cardCount <= 0) return Action.REGENERATE_SILENTLY;
        return Action.ASK_BEFORE_REGENERATE;
    }

    /** Title of the confirm dialog shown when cards exist. */
    public static String confirmTitle() {
        return "Create a new encryption key?";
    }

    /** Body of the confirm dialog: the consequence, plainly. */
    public static String confirmBody() {
        return "The encryption key can't be used. Creating a new key "
                + "means previously saved cards will stop opening and "
                + "will need to be re-added.";
    }
}
