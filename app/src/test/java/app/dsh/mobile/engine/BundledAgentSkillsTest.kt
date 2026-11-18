package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BundledAgentSkillsTest {
    private fun withRoot(test: (File, File) -> Unit) {
        val root = Files.createTempDirectory("dsh-skill-deploy-").toFile()
        try { test(File(root, "skills"), File(root, "state")) }
        finally { root.deleteRecursively() }
    }

    @Test fun `unchanged managed skill upgrades with its fingerprint`() = withRoot { root, state ->
        val name = "task-execution-discipline"
        assertTrue(BundledAgentSkills.deploy(root, state, name, "old bundled rules"))
        assertTrue(BundledAgentSkills.deploy(root, state, name, "new bundled rules"))
        assertEquals("new bundled rules", File(root, "$name/SKILL.md").readText())
    }

    @Test fun `user edits are preserved across bundle updates`() = withRoot { root, state ->
        val name = "a11y-tap-discipline"
        assertTrue(BundledAgentSkills.deploy(root, state, name, "bundled"))
        File(root, "$name/SKILL.md").writeText("my edited rules")
        assertFalse(BundledAgentSkills.deploy(root, state, name, "upgraded"))
        assertEquals("my edited rules", File(root, "$name/SKILL.md").readText())
        assertFalse(BundledAgentSkills.deploy(root, state, name, "another upgrade"))
    }

    @Test fun `preexisting unrelated skill is never claimed`() = withRoot { root, state ->
        val name = "evidence-discipline"
        File(root, "$name/SKILL.md").apply { parentFile!!.mkdirs(); writeText("user-owned") }
        assertFalse(BundledAgentSkills.deploy(root, state, name, "bundled"))
        assertFalse(File(state, "$name.sha256").exists())
        assertEquals("user-owned", File(root, "$name/SKILL.md").readText())
    }

    @Test fun `references upgrade while user-edited reference is preserved`() = withRoot { root, state ->
        val name = "task-execution-discipline"
        val path = "references/step-reminder.md"
        assertTrue(BundledAgentSkills.deploy(root, state, name, "v1", path))
        assertTrue(BundledAgentSkills.deploy(root, state, name, "v2", path))
        val reference = File(root, "$name/$path")
        assertEquals("v2", reference.readText())
        reference.writeText("my reminder")
        assertFalse(BundledAgentSkills.deploy(root, state, name, "v3", path))
        assertEquals("my reminder", reference.readText())
    }

    @Test fun `user linked skill is preserved even when content matches our last deployment`() = withRoot { root, state ->
        val name = "a11y-tap-discipline"
        assertTrue(BundledAgentSkills.deploy(root, state, name, "bundled"))
        val target = File(root, "$name/SKILL.md")
        val external = File(root.parentFile, "user-skill.md").apply { writeText("bundled") }
        target.delete()
        Files.createSymbolicLink(target.toPath(), external.toPath())
        assertFalse(BundledAgentSkills.deploy(root, state, name, "upgrade"))
        assertEquals("bundled", external.readText())
    }

    @Test fun `frontmatter removal accepts LF and CRLF and preserves body delimiter`() {
        for (newline in listOf("\n", "\r\n")) {
            val raw = listOf("---", "name: example", "description: test", "---", "", "# Rules", "---", "body").joinToString(newline)
            assertEquals("# Rules${newline}---${newline}body", BundledAgentSkills.body(raw))
        }
        assertEquals("# Rules", BundledAgentSkills.body("\n# Rules\n"))
    }

    @Test fun `deployment cannot traverse out of its managed names or files`() = withRoot { root, state ->
        assertThrows(IllegalArgumentException::class.java) {
            BundledAgentSkills.deploy(root, state, "../other", "bad")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BundledAgentSkills.deploy(root, state, "task-execution-discipline", "bad", "../../AGENTS.md")
        }
    }
}
