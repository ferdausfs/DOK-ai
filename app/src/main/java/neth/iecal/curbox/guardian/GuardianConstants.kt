package neth.iecal.curbox.guardian

/**
 * Guardian (NSFW content blocking) — detection constants.
 * Ported from Dogs-of-KAHAF / Guardian Shield, adapted to the Curbox style
 * (no Hilt, no Timber). Phase-1 false-block fixes are already applied:
 * the "soft NSFW" gender-lowering constant is gone — opposite-gender blocking
 * always requires the full gender confidence.
 */
object GuardianConstants {
    // NSFW gate threshold (max strategy on the NSFW model's unsafe indices).
    const val NSFW_GATE_THRESHOLD = 0.68f

    // Gender confidence threshold for opposite-gender blocking.
    const val GENDER_CONFIDENCE_THRESHOLD = 0.78f

    // Legacy combined classifier default threshold (user adjustable).
    const val LEGACY_THRESHOLD_DEFAULT = 0.72f

    // Full-screen screenshots are noisy (UI chrome/text/many small images), so
    // the full-screen path requires a STRONGER NSFW gate before the gender
    // model is consulted. Content-region scans keep the normal gate.
    const val FULL_SCREEN_NSFW_GATE = 0.80f

    // Model files (imported into filesDir via GuardianModelImportManager).
    const val MODEL_LEGACY = "guardian_model.tflite"
    const val MODEL_NSFW = "nsfw_model.tflite"
    const val MODEL_GENDER = "gender_model.tflite"

    // Import size ceiling.
    const val MAX_MODEL_BYTES = 500L * 1024 * 1024
}
