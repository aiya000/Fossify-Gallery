package org.fossify.gallery.interfaces

import androidx.room.*
import org.fossify.gallery.helpers.PCLOUD_PATH_SCHEME
import org.fossify.gallery.helpers.SMB_PATH_SCHEME
import org.fossify.gallery.helpers.TYPE_VIDEOS
import org.fossify.gallery.models.Medium

@Dao
interface MediumDao {
    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts = 0 AND parent_path = :path COLLATE NOCASE")
    fun getMediaFromPath(path: String): List<Medium>

    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts = 0 AND is_favorite = 1")
    fun getFavorites(): List<Medium>

    @Query("SELECT COUNT(filename) FROM media WHERE deleted_ts = 0 AND is_favorite = 1")
    fun getFavoritesCount(): Long

    // The recycle bin: every deleted row, whichever storage its file stayed on (#112). The
    // device's rows name a file in the app's own files directory under a "recycle_bin" prefix,
    // the remote ones a path in the storage's own bin folder, see PCLOUD_RECYCLE_BIN and
    // SMB_RECYCLE_BIN; what tells them apart is the scheme, and the queries below pick one
    // storage's out for the operations that go through that storage
    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts != 0")
    fun getDeletedMedia(): List<Medium>

    @Query("SELECT COUNT(filename) FROM media WHERE deleted_ts != 0")
    fun getDeletedMediaCount(): Long

    // the device's, whose paths are rewritten to the files themselves by getUpdatedDeletedMedia()
    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts != 0 AND full_path NOT LIKE '$PCLOUD_PATH_SCHEME%' AND full_path NOT LIKE '$SMB_PATH_SCHEME%'")
    fun getDeviceDeletedMedia(): List<Medium>

    // the two remote storages', listed as they are: their rows are the writers', not a listing's
    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts != 0 AND (full_path LIKE '$PCLOUD_PATH_SCHEME%' OR full_path LIKE '$SMB_PATH_SCHEME%')")
    fun getRemoteDeletedMedia(): List<Medium>

    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts != 0 AND full_path LIKE '$PCLOUD_PATH_SCHEME%'")
    fun getPCloudDeletedMedia(): List<Medium>

    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts != 0 AND full_path LIKE '$SMB_PATH_SCHEME%'")
    fun getSmbDeletedMedia(): List<Medium>

    // what the daily sweep deletes for good, one storage at a time: the rows deleted before
    // the timestamp
    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts < :timestamp AND deleted_ts != 0 AND full_path NOT LIKE '$PCLOUD_PATH_SCHEME%' AND full_path NOT LIKE '$SMB_PATH_SCHEME%'")
    fun getOldDeviceRecycleBinItems(timestamp: Long): List<Medium>

    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts < :timestamp AND deleted_ts != 0 AND full_path LIKE '$PCLOUD_PATH_SCHEME%'")
    fun getOldPCloudRecycleBinItems(timestamp: Long): List<Medium>

    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE deleted_ts < :timestamp AND deleted_ts != 0 AND full_path LIKE '$SMB_PATH_SCHEME%'")
    fun getOldSmbRecycleBinItems(timestamp: Long): List<Medium>

    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE full_path = :path COLLATE NOCASE")
    fun getMediumByPath(path: String): Medium?

    // the pCloud scanner uses this to find the rows pCloud no longer has; the prefix carries no
    // LIKE wildcards of its own
    @Query("SELECT full_path FROM media WHERE full_path LIKE :prefix || '%'")
    fun getPathsWithPrefix(prefix: String): List<String>

    // the rows themselves, for a folder rename on the share that has to carry the cached copies
    // of the media under it along with them -- the copy is named after the path, the size and the
    // modification time, and only a row knows the last two
    @Query("SELECT filename, full_path, parent_path, last_modified, date_taken, size, type, video_duration, is_favorite, deleted_ts, media_store_id FROM media WHERE full_path LIKE :prefix || '%'")
    fun getMediaWithPrefix(prefix: String): List<Medium>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(medium: Medium)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(media: List<Medium>)

    @Delete
    fun deleteMedia(vararg medium: Medium)

    @Query("DELETE FROM media WHERE full_path = :path COLLATE NOCASE")
    fun deleteMediumPath(path: String)

    @Query("UPDATE OR REPLACE media SET filename = :newFilename, full_path = :newFullPath, parent_path = :newParentPath WHERE full_path = :oldPath COLLATE NOCASE")
    fun updateMedium(oldPath: String, newParentPath: String, newFilename: String, newFullPath: String)

    @Query("UPDATE OR REPLACE media SET full_path = :newPath, deleted_ts = :deletedTS WHERE full_path = :oldPath COLLATE NOCASE")
    fun updateDeleted(newPath: String, deletedTS: Long, oldPath: String)

    // a medium back out of the bin, to a folder that may not be the one it came from
    @Query("UPDATE OR REPLACE media SET full_path = :newPath, parent_path = :newParentPath, filename = :newFilename, deleted_ts = 0 WHERE full_path = :oldPath COLLATE NOCASE")
    fun restoreDeleted(oldPath: String, newPath: String, newParentPath: String, newFilename: String)

    // after a pCloud folder was renamed: every medium under it, at any depth, gets the folder's
    // new path in place of the old one, in the full path and in the parent path alike
    @Query("UPDATE OR REPLACE media SET full_path = :newFolder || substr(full_path, length(:oldFolder) + 1), parent_path = :newFolder || substr(parent_path, length(:oldFolder) + 1) WHERE full_path LIKE :oldFolder || '/%'")
    fun updatePathsUnderFolder(oldFolder: String, newFolder: String)

    // After a medium's own bytes were replaced where they lie -- a write over a file of the
    // share. The path does not move, so nothing follows it; what changes is the size and the
    // modification time, and those two also name the cached copy of it, so a row left saying the
    // old pair would have the copy of the old content served for the new one
    @Query("UPDATE media SET size = :size, last_modified = :modified WHERE full_path = :path COLLATE NOCASE")
    fun updateSizeAndModified(path: String, size: Long, modified: Long)

    @Query("UPDATE media SET date_taken = :dateTaken WHERE full_path = :path COLLATE NOCASE")
    fun updateFavoriteDateTaken(path: String, dateTaken: Long)

    @Query("UPDATE media SET is_favorite = :isFavorite WHERE full_path = :path COLLATE NOCASE")
    fun updateFavorite(path: String, isFavorite: Boolean)

    @Query("UPDATE media SET is_favorite = 0")
    fun clearFavorites()

    // A video on a remote storage is scanned without its content being read, so its duration is
    // not known until something opens the file. Whatever first does -- the viewer on playback,
    // or a folder asked to fill its videos in -- writes it back here, and the row carries it
    // from then on
    @Query("UPDATE media SET video_duration = :duration WHERE full_path = :path COLLATE NOCASE")
    fun updateVideoDuration(path: String, duration: Int)

    // The videos of the share in one folder whose length is still unknown, so that a folder can
    // be filled in deliberately instead of a video at a time as it is played. A zero is not a
    // length of zero, it is what a scan leaves behind for a file it never opened.
    //
    // Only the share's own paths: the menu item is offered on every folder, so that nobody has
    // to work out why it is missing from this one, and a folder on the device or on pCloud has
    // to answer it with nothing rather than with videos that would then be looked for on the
    // share
    @Query("SELECT full_path FROM media WHERE deleted_ts = 0 AND parent_path = :path COLLATE NOCASE AND type = $TYPE_VIDEOS AND video_duration = 0 AND full_path LIKE '$SMB_PATH_SCHEME%'")
    fun getVideoPathsWithoutDuration(path: String): List<String>

    // the device's bin only: a remote one is emptied through its writer, a file at a time
    @Query("DELETE FROM media WHERE deleted_ts != 0 AND full_path NOT LIKE '$PCLOUD_PATH_SCHEME%' AND full_path NOT LIKE '$SMB_PATH_SCHEME%'")
    fun clearDeviceRecycleBin()
}
