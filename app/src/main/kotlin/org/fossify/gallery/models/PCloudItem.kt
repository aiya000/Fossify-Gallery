package org.fossify.gallery.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// What the gallery has to remember about a pCloud file or folder beyond what the shared "media"
// and "directories" tables hold: the id the API wants instead of a path, and the hash that
// tells whether a cached thumbnail is still the right one. One row per pseudo path
@Entity(tableName = "pcloud_items", indices = [Index(value = ["path"], unique = true)])
data class PCloudItem(
    @PrimaryKey(autoGenerate = true) var id: Long?,
    @ColumnInfo(name = "path") var path: String,
    @ColumnInfo(name = "item_id") var itemId: Long,
    @ColumnInfo(name = "is_folder") var isFolder: Boolean,
    @ColumnInfo(name = "content_hash") var contentHash: Long,
    @ColumnInfo(name = "has_thumb") var hasThumb: Boolean,
    @ColumnInfo(name = "last_scanned_at") var lastScannedAt: Long
)
