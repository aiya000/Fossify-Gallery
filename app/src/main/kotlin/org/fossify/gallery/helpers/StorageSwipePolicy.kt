package org.fossify.gallery.helpers

// Whether a sideways drag of the folder list may turn it to another storage, see
// MainActivity.StorageSwipe. Out here so it can be asked without a screen: `input swipe` does not
// make a drag the list takes, so a driving script cannot reach this.
//
// Only at the top of the folder list (#136). Inside a group or a folder of grouped subfolders the
// drag used to switch storage all the same, and landed on the same group on the next storage --
// which usually holds nothing of it, so the screen read "This group is empty", a place nobody
// asked to go. Beyond that: not with only one storage, not while the last switch is still
// sliding, not while the list scrolls sideways (a sideways drag is the scroll then), and not
// while folders are selected (a drag reorder or a drag selection ends with a sideways move too)
fun canSwipeStorage(
    storageCount: Int,
    isAnimating: Boolean,
    scrollsHorizontally: Boolean,
    isSelecting: Boolean,
    isInsideGroup: Boolean,
    isInsideFolder: Boolean
): Boolean = storageCount > 1
    && !isAnimating
    && !scrollsHorizontally
    && !isSelecting
    && !isInsideGroup
    && !isInsideFolder
