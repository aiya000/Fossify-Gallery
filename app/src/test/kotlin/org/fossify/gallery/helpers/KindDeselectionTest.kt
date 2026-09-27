package org.fossify.gallery.helpers

import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem
import org.fossify.gallery.models.ThumbnailSection
import org.junit.Assert.assertEquals
import org.junit.Test

// What "Deselect photos" and "Deselect videos" take out of a folder's selection (#138): only the
// selected media of that one kind, whatever else is selected or on the grid
class KindDeselectionTest {
    private fun medium(name: String, type: Int) = Medium().apply {
        this.name = name
        path = "/storage/emulated/0/Pictures/Mixed/$name"
        this.type = type
    }

    private val grid: List<ThumbnailItem> = listOf(
        ThumbnailSection("Today"),
        medium("a.jpg", TYPE_IMAGES),     // 1
        medium("b.mp4", TYPE_VIDEOS),     // 2
        medium("c.gif", TYPE_GIFS),       // 3
        ThumbnailSection("Yesterday"),
        medium("d.dng", TYPE_RAWS),       // 5
        medium("e.svg", TYPE_SVGS),       // 6
        medium("f.jpg", TYPE_PORTRAITS),  // 7
        medium("g.mkv", TYPE_VIDEOS)      // 8
    )

    private fun selecting(vararg positions: Int): (Int) -> Boolean = { positions.contains(it) }

    private val everything = selecting(1, 2, 3, 5, 6, 7, 8)

    @Test
    fun `deselecting photos takes out every image kind and leaves the videos`() {
        assertEquals(listOf(1, 3, 5, 6, 7), positionsToDeselect(grid, videos = false, isSelected = everything))
    }

    @Test
    fun `deselecting videos takes out the videos and leaves every photo`() {
        assertEquals(listOf(2, 8), positionsToDeselect(grid, videos = true, isSelected = everything))
    }

    // what was not selected is left as it was, rather than toggled on
    @Test
    fun `media that were not selected are not touched`() {
        assertEquals(listOf(3), positionsToDeselect(grid, videos = false, isSelected = selecting(2, 3, 8)))
    }

    @Test
    fun `a selection with none of the kind gives nothing to deselect`() {
        assertEquals(emptyList<Int>(), positionsToDeselect(grid, videos = true, isSelected = selecting(1, 5)))
    }

    // a section title is never a medium, even when its position is asked about
    @Test
    fun `section titles are neither kind`() {
        val all = selecting(*grid.indices.toList().toIntArray())
        assertEquals(listOf(2, 8), positionsToDeselect(grid, videos = true, isSelected = all))
        assertEquals(listOf(1, 3, 5, 6, 7), positionsToDeselect(grid, videos = false, isSelected = all))
    }
}
