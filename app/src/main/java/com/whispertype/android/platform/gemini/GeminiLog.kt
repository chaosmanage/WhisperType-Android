package com.whispertype.android.platform.gemini

/**
 * JVM-safe logger for the Gemini session. The Android mockable `android.jar`
 * used by host unit tests stubs `android.util.Log` (`RuntimeException: Stub!`),
 * so direct `Log` calls would crash the JVM contract tests. This wrapper
 * resolves `android.util.Log` only when a real Android runtime is present and
 * no-ops otherwise (see docs/GEMINI_LIVE_PROTOCOL.md §6).
 */
object GeminiLog {

    fun i(tag: String, message: String) = log("i", tag, message)

    fun w(tag: String, message: String) = log("w", tag, message)

    private fun log(level: String, tag: String, message: String) {
        try {
            val logClass = Class.forName("android.util.Log")
            val method = logClass.getMethod(level, String::class.java, String::class.java)
            method.invoke(null, tag, message)
        } catch (_: Throwable) {
            // Host JVM: android.util.Log is stubbed; swallow silently.
        }
    }
}
