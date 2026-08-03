package com.whispertype.android.audit

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Scans the on-disk sources for Google/OpenAI-style API key literals, authenticated
 * URL literals, sensitive log lines, and committed keystore or secret files.
 * Runs as a JVM test with the working directory set to the app module directory.
 */
class SourcePrivacyAuditTest {

    private val workingDir: File = File(System.getProperty("user.dir") ?: ".")
    private val sourceExtensions = setOf("kt", "xml", "toml", "kts")
    private val googleApiKeyPattern = Regex("AIza[0-9A-Za-z_-]{30,}")
    private val openAiApiKeyPattern = Regex("sk-[0-9A-Za-z_-]{20,}")
    private val sensitiveLogTerms =
        listOf("apiKey", "getApiKey", "transcript", "secret", "clipboard", "authorization", "url")
    private val bannedNames = setOf("signing.properties", ".env")
    private val bannedSuffixes = listOf(".jks", ".keystore")

    /** No main-sourceset file may contain a Google API key literal. */
    @Test
    fun mainSourcesContainNoGoogleApiKeyLiterals() {
        val violations = violating(mainFiles()) { googleApiKeyPattern.containsMatchIn(it.readText()) }
        assertTrue("Google API key literals: ${describe(violations)}", violations.isEmpty())
    }

    /** No main-sourceset file may contain an OpenAI-style API key literal. */
    @Test
    fun mainSourcesContainNoOpenAiApiKeyLiterals() {
        val violations = violating(mainFiles()) { openAiApiKeyPattern.containsMatchIn(it.readText()) }
        assertTrue("OpenAI-style API key literals: ${describe(violations)}", violations.isEmpty())
    }

    /** No main-sourceset line may combine the Gemini endpoint with an inline key. */
    @Test
    fun mainSourcesContainNoAuthenticatedUrlLiterals() {
        val violations = violating(mainFiles()) { file ->
            file.readLines().any { line ->
                line.contains("generativelanguage.googleapis.com/ws") &&
                    line.contains("key=") &&
                    !line.contains("redact")
            }
        }
        assertTrue("Authenticated URL literals: ${describe(violations)}", violations.isEmpty())
    }

    /** No main-sourceset log line may reference sensitive payloads. */
    @Test
    fun mainSourcesContainNoSensitiveLogLines() {
        val violations = violating(mainFiles()) { file ->
            file.readLines().any { line ->
                line.contains("Log.") &&
                    !line.contains("redact") &&
                    !line.contains("SecretRedactor") &&
                    sensitiveLogTerms.any { line.contains(it) }
            }
        }
        assertTrue("Sensitive log lines: ${describe(violations)}", violations.isEmpty())
    }

    /** The repo must not contain keystores, signing.properties, or .env files. */
    @Test
    fun repoContainsNoSecretFiles() {
        val parent = checkNotNull(workingDir.parentFile) { "working directory has no parent" }
        val roots = listOf(workingDir, parent)
        val violations = mutableListOf<File>()
        for (root in roots) {
            root.walkTopDown().forEach { file ->
                if (file.isFile && isBannedName(file.name)) {
                    violations += file
                }
            }
        }
        assertTrue("Secret files in repo: ${describe(violations)}", violations.isEmpty())
    }

    private fun mainFiles(): List<File> {
        val mainDir = workingDir.resolve("src/main")
        assumeTrue("src/main not found under ${workingDir.path}; test must run in the app module", mainDir.isDirectory)
        return collectSources(mainDir)
    }

    private fun collectSources(root: File): List<File> =
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension in sourceExtensions }
            .toList()

    private fun violating(files: List<File>, check: (File) -> Boolean): List<File> = files.filter { check(it) }

    private fun isBannedName(name: String): Boolean =
        name in bannedNames || bannedSuffixes.any { name.endsWith(it) }

    private fun describe(files: List<File>): String = files.joinToString { it.path }
}
