package app.dsh.mobile.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeToolingShimsTest {
    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("dsh tooling ").toFile()
        try {
            File(root, "bin").mkdirs()
            File(root, "bin/node").apply {
                writeText("#!/bin/sh\nprintf '%s\\n' \"\$@\"\n")
                setExecutable(true)
            }
            test(root)
        } finally { root.deleteRecursively() }
    }

    private fun run(root: File, tool: String, env: Map<String, String> = emptyMap()): Pair<Int, String> {
        val script = File(root, "bin/$tool").apply { writeText(NodeToolingShims.script(tool)) }
        val process = ProcessBuilder("sh", script.path, "arg with spaces", "--version")
            .redirectErrorStream(true).apply {
                environment().keys.removeAll { it.startsWith("DSH_") }
                environment().putAll(env)
            }.start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun `missing npm produces actionable error`() = withRoot { root ->
        val (code, output) = run(root, "npm")
        assertEquals(127, code)
        assertTrue(output.contains("DSH_NPM_CLI"))
        assertTrue(output.contains("ENGINE-BUILD.md"))
    }

    @Test fun `independent npm receives arguments intact without corepack`() = withRoot { root ->
        val cli = File(root, "lib/node_modules/npm/bin/npm-cli.js").apply {
            parentFile!!.mkdirs(); writeText("")
        }
        val (code, output) = run(root, "npm")
        assertEquals(0, code)
        val received = output.trimEnd().lines()
        assertEquals(cli.canonicalPath, File(received.first()).canonicalPath)
        assertEquals(listOf("arg with spaces", "--version"), received.drop(1))
    }

    @Test fun `explicit CLI takes priority and invalid override fails`() = withRoot { root ->
        val cli = File(root, "my npm.js").apply { writeText("") }
        val (code, output) = run(root, "npm", mapOf("DSH_NPM_CLI" to cli.path))
        assertEquals(0, code)
        assertTrue(output.startsWith(cli.path + "\n"))
        assertEquals(127, run(root, "npm", mapOf("DSH_NPM_CLI" to File(root, "absent").path)).first)
    }

    @Test fun `bundled pnpm works without corepack`() = withRoot { root ->
        val home = File(root, "home directory")
        val cli = File(home, "profiles/web/node_modules/pnpm/bin/pnpm.cjs").apply {
            parentFile!!.mkdirs(); writeText("")
        }
        val (code, output) = run(root, "pnpm", mapOf("DSH_HOME" to home.path))
        assertEquals(0, code)
        assertTrue(output.startsWith(cli.path + "\n"))
    }

    @Test fun `pnpm mjs backend is supported`() = withRoot { root ->
        val home = File(root, "home directory")
        val cli = File(home, "profiles/web/node_modules/pnpm/bin/pnpm.mjs").apply {
            parentFile!!.mkdirs(); writeText("")
        }
        val (code, output) = run(root, "pnpm", mapOf("DSH_HOME" to home.path))
        assertEquals(0, code)
        assertEquals(cli.canonicalPath, File(output.lineSequence().first()).canonicalPath)
    }

    @Test fun `corepack JS backend uses node explicitly`() = withRoot { root ->
        val cli = File(root, "lib/node_modules/corepack/dist/corepack.js").apply {
            parentFile!!.mkdirs(); writeText("")
        }
        val (code, output) = run(root, "corepack")
        assertEquals(0, code)
        assertEquals(cli.canonicalPath, File(output.lineSequence().first()).canonicalPath)
    }

    @Test fun `npx can use a present corepack backend`() = withRoot { root ->
        val cli = File(root, "lib/node_modules/corepack/dist/npx.js").apply {
            parentFile!!.mkdirs(); writeText("")
        }
        val (code, output) = run(root, "npx")
        assertEquals(0, code)
        assertEquals(cli.canonicalPath, File(output.lineSequence().first()).canonicalPath)
    }
}
