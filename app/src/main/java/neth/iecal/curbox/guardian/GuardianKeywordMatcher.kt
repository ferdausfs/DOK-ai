package neth.iecal.curbox.guardian

import java.util.regex.Pattern

/**
 * Keyword / regex matcher for text-based NSFW detection.
 *
 * Ported from Dogs-of-KAHAF `RulesEngine.evaluateText`, with the Phase-1
 * false-block fix: regex keywords are wrapped in word boundaries unless the
 * user already anchored them, so a keyword like "sex" cannot match inside
 * "Essex" / "sextant".
 *
 * Pure logic — no Android dependencies (unit-testable on the JVM).
 */
object GuardianKeywordMatcher {

    sealed class Result {
        object Allow : Result()
        data class Block(val matched: String) : Result()
    }

    /**
     * @param rules list of (pattern, isRegex) pairs.
     * @param text  visible text collected from the accessibility tree.
     */
    fun evaluate(text: String, rules: List<Pair<String, Boolean>>): Result {
        if (text.isBlank() || text.length < 2) return Result.Allow
        for ((kw, isRegex) in rules) {
            try {
                if (isRegex) {
                    // FALSE-BLOCK FIX: bare user regexes get word boundaries so a
                    // keyword can't match inside a larger word. Regexes the user
                    // already anchored (^, $, \b, \B) keep their exact semantics.
                    val raw = kw.trim()
                    val alreadyAnchored = raw.contains('^') || raw.contains('$') ||
                        raw.startsWith("\\b") || raw.startsWith("\\B")
                    val pattern = if (alreadyAnchored) raw else "(?iu)\\b(?:$raw)\\b"
                    if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
                            .matcher(text).find()) {
                        return Result.Block(kw)
                    }
                } else {
                    // Plain keywords: exact word-boundary match (same semantics
                    // as the original non-regex path).
                    val pattern = "(?iu)\\b${Pattern.quote(kw)}\\b"
                    if (Pattern.compile(pattern).matcher(text).find()) {
                        return Result.Block(kw)
                    }
                }
            } catch (_: Throwable) {
                // invalid regex — skip, don't crash the scan
            }
        }
        return Result.Allow
    }
}
