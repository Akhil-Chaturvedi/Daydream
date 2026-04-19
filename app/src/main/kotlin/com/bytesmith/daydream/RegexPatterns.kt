package com.bytesmith.daydream

import java.util.regex.Pattern

/**
 * Shared regex patterns used across the application.
 * Consolidated to avoid duplication between NotificationService and MediaSessionTracker.
 */
object RegexPatterns {
    val EMPTY_BRACKET = Pattern.compile("""\([\s-]*\)|\[[\s-]*\]""")
    val MULTIPLE_SPACE = Pattern.compile("""\s{2,}""")
    val WHITESPACE = Pattern.compile("""\s+""")
    val PUNCTUATION = Pattern.compile("""[\p{Punct}]""")
}