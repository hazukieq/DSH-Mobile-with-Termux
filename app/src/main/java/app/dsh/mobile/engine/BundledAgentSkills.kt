package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** Bundled rules are preloaded from APK assets; discovery copies preserve user edits. */
internal object BundledAgentSkills {
    val names = listOf("task-execution-discipline", "a11y-tap-discipline", "evidence-discipline")

    // Known unmarked originals: the legacy bundle and the user-provided tap skill.
    private val legacyHashes = mapOf(
        "evidence-discipline" to "72e6aaef7cdb5b87344448cba44314b24eb597e6b52f0d8b7c62cedf4bed3473",
        "a11y-tap-discipline" to "e8bca584831c97b5e1b5d1bffb023c465b7b8accd470374ca187703bca0d8cfc",
    )

    fun body(markdown: String): String = markdown.replaceFirst(
        Regex("\\A---\\r?\\n[\\s\\S]*?\\r?\\n---(?:\\r?\\n|$)"), "",
    ).trim()

    /** False means an existing copy differs from both our last deployment and known legacy content. */
    fun deploy(root: File, state: File, name: String, text: String, relativePath: String = "SKILL.md"): Boolean {
        require(name in names)
        require(relativePath == "SKILL.md" ||
            (name == "task-execution-discipline" && relativePath == "references/step-reminder.md"))
        val target = File(root, "$name/$relativePath")
        if (listOf(root, File(root, name), target.parentFile, target).any {
                Files.isSymbolicLink(it.toPath())
            }) return false
        val key = if (relativePath == "SKILL.md") name else "$name-step-reminder"
        val fingerprint = File(state, "$key.sha256")
        val current = if (target.isFile) target.readText() else null
        if (current != null && current != text) {
            val previous = if (fingerprint.isFile) fingerprint.readText().trim() else null
            val digest = sha256(current)
            if (digest != previous && (relativePath != "SKILL.md" || digest != legacyHashes[name])) return false
        }
        if (current != text) {
            target.parentFile!!.mkdirs()
            target.writeText(text)
        }
        state.mkdirs()
        fingerprint.writeText(sha256(text))
        return true
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
