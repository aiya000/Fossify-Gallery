package org.fossify.gallery

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

// #74: a catch that swallows a failure without a word looks exactly like "nothing happened",
// which is the hardest thing to report and the hardest thing to debug. Every catch in the app's
// own sources either does something -- says it on the screen, logs it under QuietFailure -- or
// carries a comment saying why there is nothing to do. This reads the sources themselves, so
// that the next empty catch is caught before it swallows anything
class NoSilentCatchTest {
    // a catch whose body holds nothing but whitespace. A body with a comment in it does not match
    private val silentCatch = Regex("""catch \([^)]*\) \{\s*\}""")

    @Test
    fun `no catch in the app's sources is empty`() {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).firstOrNull { it.isDirectory }
            ?: throw AssertionError("no kotlin sources next to ${File(".").absolutePath}")

        val silent = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                silentCatch.findAll(text).map { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    "${file.relativeTo(root)}:$line"
                }
            }
            .toList()

        assertEquals("every empty catch either acts or says why it does not (see helpers/QuietFailure.kt)", emptyList<String>(), silent)
    }
}
