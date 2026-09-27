package org.fossify.gallery.helpers

import org.fossify.gallery.models.Medium
import org.fossify.gallery.models.ThumbnailItem

// "Deselect photos" and "Deselect videos" of a folder's selection menu (#138): the positions of
// the selected media of one kind, for the adapter to deselect one by one. Everything that is not a
// video is a photo -- GIFs, RAWs, SVGs and portraits too -- so the two between them cover every
// medium, and the section titles of a grouped grid are neither
fun positionsToDeselect(items: List<ThumbnailItem>, videos: Boolean, isSelected: (Int) -> Boolean): List<Int> =
    items.indices.filter { position ->
        val medium = items[position] as? Medium
        medium != null && medium.isVideo() == videos && isSelected(position)
    }
