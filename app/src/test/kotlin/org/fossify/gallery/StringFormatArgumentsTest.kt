package org.fossify.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Every string this fork adds goes into values/ and values-ja/ in the same commit, and the ones
// that carry a number or a name carry it as a positional argument. If the two copies disagree
// about those arguments -- a %1$s where the other has %1$d, or an argument dropped from one side
// -- getString() throws, and it throws only on the device set to the locale that got it wrong.
// The confirmation before a move joins two folders is one of these, and it is shown at the very
// moment there is something to lose, so it is the last place for a crash.
class StringFormatArgumentsTest {
    private val placeholder = Regex("""%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])""")

    @Test
    fun `every translated string takes the same format arguments as the original`() {
        val original = readStrings("values")
        val translated = readStrings("values-ja")

        // a string that is not translated at all falls back to the original, which is fine;
        // one that is translated has to be callable in the same way
        translated.forEach { (name, japanese) ->
            val english = original[name] ?: return@forEach
            assertEquals(
                "the format arguments of \"$name\" differ between values/ and values-ja/",
                argumentsOf(english),
                argumentsOf(japanese)
            )
        }
    }

    // the one this branch adds, named on its own so that a run says what broke rather than
    // only that something did
    @Test
    fun `the folder join confirmation takes a name and a count, in both locales`() {
        listOf("values", "values-ja").forEach { dir ->
            val message = readStrings(dir)["move_folder_into_folder_confirmation"]
            assertTrue("move_folder_into_folder_confirmation is missing from $dir/", message != null)
            assertEquals("the arguments of the join confirmation changed in $dir/", setOf("1s", "2d"), argumentsOf(message!!))
        }
    }

    // "1s" for %1$s: the position and the conversion together, since either one differing is
    // what throws. An argument without a position takes the place it appears in
    private fun argumentsOf(value: String): Set<String> {
        var implicitPosition = 0
        return placeholder.findAll(value)
            .filter { it.groupValues[2] != "%" }
            .map {
                val position = it.groupValues[1].ifEmpty { (++implicitPosition).toString() }
                "$position${it.groupValues[2]}"
            }
            .toSet()
    }

    private fun readStrings(valuesDir: String): Map<String, String> {
        val file = resDir().resolve("$valuesDir/strings.xml")
        assertTrue("${file.path} is not where this test looked for it", file.isFile)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    // the unit tests run from the module directory, but say so out loud rather than reporting a
    // missing file as a missing string
    private fun resDir(): File {
        val candidates = listOf(File("src/main/res"), File("app/src/main/res"))
        return candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError("no res directory next to ${File(".").absolutePath}")
    }
}
