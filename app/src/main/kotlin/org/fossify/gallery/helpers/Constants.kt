package org.fossify.gallery.helpers

import org.fossify.commons.helpers.*

// shared preferences
const val DIRECTORY_SORT_ORDER = "directory_sort_order"
const val GROUP_FOLDER_PREFIX = "group_folder_"
const val VIEW_TYPE_PREFIX = "view_type_folder_"
const val SHOW_HIDDEN_MEDIA = "show_hidden_media"
const val TEMPORARILY_SHOW_HIDDEN = "temporarily_show_hidden"
const val TEMPORARILY_SHOW_EXCLUDED = "temporarily_show_excluded"
const val EXCLUDED_PASSWORD_PROTECTION = "excluded_password_protection"
const val EXCLUDED_PASSWORD_HASH = "excluded_password_hash"
const val EXCLUDED_PROTECTION_TYPE = "excluded_protection_type"
const val IS_THIRD_PARTY_INTENT = "is_third_party_intent"
const val AUTOPLAY_VIDEOS = "autoplay_videos"
const val REMEMBER_LAST_VIDEO_POSITION = "remember_last_video_position"
const val LOOP_VIDEOS = "loop_videos"
const val MUTE_VIDEOS = "mute_videos"
const val GESTURE_VIDEO_PLAYER = "open_videos_on_separate_screen"
const val VIDEO_PLAYER_TYPE = "video_player_type"
const val ANIMATE_GIFS = "animate_gifs"
const val MAX_BRIGHTNESS = "max_brightness"
const val ULTRA_HDR_RENDERING = "ultra_hdr_rendering"
const val PLAYBACK_SPEED = "playback_speed"
const val PLAYBACK_SPEED_PROGRESS = "playback_speed_progress"
const val CROP_THUMBNAILS = "crop_thumbnails"
const val SHOW_THUMBNAIL_VIDEO_DURATION = "show_thumbnail_video_duration"
const val SCREEN_ROTATION = "screen_rotation"
const val DISPLAY_FILE_NAMES = "display_file_names"
const val BLACK_BACKGROUND = "dark_background"
const val PINNED_FOLDERS = "pinned_folders"
const val FILTER_MEDIA = "filter_media"
const val DEFAULT_FOLDER = "default_folder"
const val DIR_COLUMN_CNT = "dir_column_cnt"
const val DIR_LANDSCAPE_COLUMN_CNT = "dir_landscape_column_cnt"
const val DIR_HORIZONTAL_COLUMN_CNT = "dir_horizontal_column_cnt"
const val DIR_LANDSCAPE_HORIZONTAL_COLUMN_CNT = "dir_landscape_horizontal_column_cnt"
const val MEDIA_COLUMN_CNT = "media_column_cnt"
const val MEDIA_LANDSCAPE_COLUMN_CNT = "media_landscape_column_cnt"
const val MEDIA_HORIZONTAL_COLUMN_CNT = "media_horizontal_column_cnt"
const val MEDIA_LANDSCAPE_HORIZONTAL_COLUMN_CNT = "media_landscape_horizontal_column_cnt"
const val SHOW_ALL = "show_all"                           // display images and videos from all folders together
const val HIDE_FOLDER_TOOLTIP_SHOWN = "hide_folder_tooltip_shown"
const val EXCLUDED_FOLDERS = "excluded_folders"
const val INCLUDED_FOLDERS = "included_folders"
const val ALBUM_COVERS = "album_covers"
const val HIDE_SYSTEM_UI = "hide_system_ui"
const val DELETE_EMPTY_FOLDERS = "delete_empty_folders"
const val KEEP_SCREEN_ON = "keep_screen_on"
const val ALLOW_PHOTO_GESTURES = "allow_photo_gestures"
const val ALLOW_VIDEO_GESTURES = "allow_video_gestures"
const val TEMP_FOLDER_PATH = "temp_folder_path"
const val VIEW_TYPE_FOLDERS = "view_type_folders"
const val VIEW_TYPE_FILES = "view_type_files"
const val SHOW_EXTENDED_DETAILS = "show_extended_details"
const val EXTENDED_DETAILS = "extended_details"
const val HIDE_EXTENDED_DETAILS = "hide_extended_details"
const val ALLOW_INSTANT_CHANGE = "allow_instant_change"
const val WAS_NEW_APP_SHOWN = "was_new_app_shown_clock"
const val LAST_FILEPICKER_PATH = "last_filepicker_path"
const val LAST_COPY_PATH = "last_copy_path"
const val TEMP_SKIP_DELETE_CONFIRMATION = "temp_skip_delete_confirmation"
const val TEMP_SKIP_RECYCLE_BIN = "temp_skip_recycle_bin"
const val BOTTOM_ACTIONS = "bottom_actions"
const val LAST_VIDEO_POSITION_PREFIX = "last_video_position_"
const val VISIBLE_BOTTOM_ACTIONS = "visible_bottom_actions"
const val WERE_FAVORITES_PINNED = "were_favorites_pinned"
const val WAS_RECYCLE_BIN_PINNED = "was_recycle_bin_pinned"
const val USE_RECYCLE_BIN = "use_recycle_bin"
const val GROUP_BY = "group_by"
const val EVER_SHOWN_FOLDERS = "ever_shown_folders"
const val SHOW_RECYCLE_BIN_AT_FOLDERS = "show_recycle_bin_at_folders"
const val SHOW_RECYCLE_BIN_LAST = "show_recycle_bin_last"
const val ALLOW_ZOOMING_IMAGES = "allow_zooming_images"
const val WAS_SVG_SHOWING_HANDLED = "was_svg_showing_handled"
const val LAST_BIN_CHECK = "last_bin_check"
const val SHOW_HIGHEST_QUALITY = "show_highest_quality"
const val ALLOW_DOWN_GESTURE = "allow_down_gesture"
const val LAST_EDITOR_CROP_ASPECT_RATIO = "last_editor_crop_aspect_ratio"
const val LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_X = "last_editor_crop_other_aspect_ratio_x_2"
const val LAST_EDITOR_CROP_OTHER_ASPECT_RATIO_Y = "last_editor_crop_other_aspect_ratio_y_2"
const val GROUP_DIRECT_SUBFOLDERS = "group_direct_subfolders"
const val SHOW_WIDGET_FOLDER_NAME = "show_widget_folder_name"
const val ALLOW_ONE_TO_ONE_ZOOM = "allow_one_to_one_zoom"
const val ALLOW_ROTATING_WITH_GESTURES = "allow_rotating_with_gestures"
const val LAST_EDITOR_DRAW_COLOR = "last_editor_draw_color"
const val LAST_EDITOR_BRUSH_SIZE = "last_editor_brush_size"
const val SHOW_NOTCH = "show_notch"
const val FILE_LOADING_PRIORITY = "file_loading_priority"
const val SPAM_FOLDERS_CHECKED = "spam_folders_checked"
const val SHOW_THUMBNAIL_FILE_TYPES = "show_thumbnail_file_types"
const val MARK_FAVORITE_ITEMS = "mark_favorite_items"
const val EDITOR_BRUSH_COLOR = "editor_brush_color"
const val EDITOR_BRUSH_HARDNESS = "editor_brush_hardness"
const val EDITOR_BRUSH_SIZE = "editor_brush_size"
const val WERE_FAVORITES_MIGRATED = "were_favorites_migrated"
const val FOLDER_THUMBNAIL_STYLE = "folder_thumbnail_style"
const val FOLDER_MEDIA_COUNT = "folder_media_count"
const val LIMIT_FOLDER_TITLE = "folder_limit_title"
const val THUMBNAIL_SPACING = "thumbnail_spacing"
const val FILE_ROUNDED_CORNERS = "file_rounded_corners"
const val CUSTOM_FOLDERS_ORDER = "custom_folders_order"
const val CUSTOM_FOLDERS_ORDER_SEPARATOR = "|||"

// the custom media order is kept per folder, so the folder path is appended to the prefix
const val CUSTOM_MEDIA_ORDER_PREFIX = "custom_media_order_"
const val CUSTOM_MEDIA_ORDER_SEPARATOR = "|||"

// the paths selected in the media grid, handed to the fullscreen view and back again
const val SELECTED_PATHS = "selected_paths"

// not a sorting criterion of its own, it is combined with SORT_BY_DATE_TAKEN or
// SORT_BY_DATE_MODIFIED. The commons SORT_BY_* flags end at SORT_BY_COUNT (524288)
const val SORT_GROUP_BY_FILENAME = 1048576

const val INCLUDE_SORTING_IN_SETTINGS_EXPORT = "include_sorting_in_settings_export"

const val FOLDER_GROUPS = "folder_groups"
const val FOLDER_GROUP_MEMBERS = "folder_group_members"

// virtual folder groups are displayed as Directory items with this pseudo path prefix, e.g. "group://12"
const val GROUP_PATH_PREFIX = "group://"

// the application id this fork was renamed from. It is still the `namespace`, and SimpleActivity
// hands it to the commons anti-tampering check so this build stops calling itself a fake
const val UPSTREAM_APP_ID = "org.fossify.gallery"

// pCloud media is displayed with this pseudo path prefix, e.g. "pcloud:/Camera/IMG_0001.jpg".
// It mirrors the pCloud path, so getParentPath() and the rest of the path handling keep working.
// The pCloud root itself is "pcloud:" with no slash, which is what getParentPath() hands back
// for a file lying directly in the root, so Medium.parentPath == Directory.path holds there too
const val PCLOUD_PATH_SCHEME = "pcloud:"
const val PCLOUD_PATH_PREFIX = "$PCLOUD_PATH_SCHEME/"

// The app's own recycle bin on pCloud: a folder in the root that deleted media are moved
// into, see PCloudWriter.moveToRecycleBin(). The folder is a real one, but the rows of the
// media in it keep their original layout under this pseudo path, e.g.
// "pcloud:/.gallery-recycle-bin/Camera/IMG_0001.jpg" for a file that was in "pcloud:/Camera",
// so that a restore knows where the file came from; where the file lies inside the folder
// on pCloud never matters, every operation on it goes by its file id. The folder list shows
// the bin as a folder with this pseudo path, like RECYCLE_BIN for the device's bin
const val PCLOUD_RECYCLE_BIN_FOLDER_NAME = ".gallery-recycle-bin"
const val PCLOUD_RECYCLE_BIN = "$PCLOUD_PATH_PREFIX$PCLOUD_RECYCLE_BIN_FOLDER_NAME"

// under the cache directory: the pCloud media handed to the rest of the app as real files,
// each a link to its cached copy under the name the medium has on pCloud
const val PCLOUD_WORK_DIR = "pcloud-work"

// under the cache directory: the copy an editor is working on. A copy, not a link, so that a
// half-written edit cannot reach the cache, and a directory of its own so that the editor
// can tell it is editing a pCloud medium and offer to save it back rather than beside itself
const val PCLOUD_EDIT_DIR = "pcloud-edit"

// under the cache directory: the resized image on its way to a pCloud folder, under the name
// it is to have there. Only one resize is in flight at a time, so the folder is emptied first
const val PCLOUD_RESIZE_DIR = "pcloud-resize"

// under the cache directory: the media handed over by the share sheet, written out under the
// name they are shared as. A share arrives as content:// uris and the copy works on real
// paths, so they are staged here and copied from here. Emptied at the start of every share
const val SHARED_MEDIA_DIR = "shared-incoming"

const val PCLOUD_ACCESS_TOKEN = "pcloud_access_token"
const val PCLOUD_API_HOST = "pcloud_api_host"
const val PCLOUD_ACCOUNT_EMAIL = "pcloud_account_email"

// the "state" handed to pCloud when the browser is opened, compared against the one that comes
// back so a redirect fired by anything but our own login attempt is ignored
const val PCLOUD_OAUTH_STATE = "pcloud_oauth_state"

// pCloud hands the token back through this scheme, see PCloudAuthActivity and the manifest
const val PCLOUD_OAUTH_SCHEME = "pcloud-oauth"
const val PCLOUD_AUTHORIZE_URL = "https://my.pcloud.com/oauth2/authorize"

// every pCloud response carries a "result", 0 means success. These two mean the token is no
// longer good, there is no other notice that it expired
const val PCLOUD_RESULT_LOG_IN_FAILED = 1000
const val PCLOUD_RESULT_LOG_IN_REQUIRED = 2000
// undocumented; seen for a recursive listfolder of the root, which pCloud refuses
const val PCLOUD_RESULT_INVALID_REQUEST = 1101

// listfolder answers this for a folder that has been deleted or moved since it was scanned
const val PCLOUD_RESULT_DIRECTORY_NOT_FOUND = 2005
// "File not found", the file half of the above
const val PCLOUD_RESULT_FILE_NOT_FOUND = 2009
// "File or folder already exists", what createfolder answers for a name that is taken
const val PCLOUD_RESULT_ALREADY_EXISTS = 2004

// pCloud's own idea of what a file is, carried as "category" in file metadata. Used as the
// fallback when the filename extension tells nothing
const val PCLOUD_CATEGORY_IMAGE = 1
const val PCLOUD_CATEGORY_VIDEO = 2

// SMB media is displayed with this pseudo path prefix, the same way pCloud media is, e.g.
// "smb:/photos/2026/IMG_0001.jpg" for "\\host\photos\2026\IMG_0001.jpg". The share name is the
// first segment, so one configured host is browsed as one tree and getParentPath() keeps working
const val SMB_PATH_SCHEME = "smb:"
const val SMB_PATH_PREFIX = "$SMB_PATH_SCHEME/"

// under the cache directory: the local copies of SMB files, for the parts of the app that need
// a real file. See SmbFileCache
const val SMB_CACHE_DIR = "smb"

// the share is reached with these; the password is kept in the app's private prefs like the
// pCloud token is. An empty user name means a guest connection
const val SMB_HOST = "smb_host"
const val SMB_PORT = "smb_port"
const val SMB_SHARE = "smb_share"
// the folder inside the share to treat as the root, "" for the share itself
const val SMB_ROOT_PATH = "smb_root_path"
const val SMB_USER = "smb_user"
const val SMB_PASSWORD = "smb_password"
const val SMB_DOMAIN = "smb_domain"

// when the SMB cache is refreshed from the share, mirroring the pCloud rescan settings. A share
// that is not reachable has to fail fast, so a scan is never started on a metered connection
const val SMB_RESCAN_ON_LAUNCH = "smb_rescan_on_launch"
const val SMB_RESCAN_ON_STORAGE_SWITCH = "smb_rescan_on_storage_switch"
const val SMB_RESCAN_ON_FOLDER_OPEN = "smb_rescan_on_folder_open"
const val SMB_RESCAN_ON_UNMETERED_ONLY = "smb_rescan_on_unmetered_only"
const val SMB_RESCAN_INTERVAL_MINUTES = "smb_rescan_interval_minutes"
const val SMB_LAST_FULL_SCAN_AT = "smb_last_full_scan_at"

// the default SMB port; 139 is the NetBIOS one, offered for an old server
const val SMB_DEFAULT_PORT = 445
const val STORAGE_FILTER = "storage_filter"
// the folder list sorting of one storage, the STORAGE_FILTER_* value follows; see Config.directorySorting
const val SORT_FOLDERS_STORAGE_PREFIX = "sort_folders_storage_"
// in a sorting export only: which storages carry a sorting of their own, see Config.getSortingPreferences()
const val SORT_FOLDERS_OWN_STORAGES = "sort_folders_own_storages"

// when the pCloud cache is refreshed from the network, see PCloudSyncPolicy. Only launch and
// storage switch are wired up so far, the other events land with the screens that raise them
const val PCLOUD_RESCAN_ON_LAUNCH = "pcloud_rescan_on_launch"
const val PCLOUD_RESCAN_ON_STORAGE_SWITCH = "pcloud_rescan_on_storage_switch"
const val PCLOUD_RESCAN_ON_FOLDER_OPEN = "pcloud_rescan_on_folder_open"
const val PCLOUD_RESCAN_ON_GROUP_OPEN = "pcloud_rescan_on_group_open"
const val PCLOUD_RESCAN_AFTER_WRITE = "pcloud_rescan_after_write"
const val PCLOUD_RESCAN_INTERVAL_MINUTES = "pcloud_rescan_interval_minutes"
const val PCLOUD_RESCAN_ON_UNMETERED_ONLY = "pcloud_rescan_on_unmetered_only"
// pCloud folders hidden in this app, see Config.pCloudHiddenFolders
const val PCLOUD_HIDDEN_FOLDERS = "pcloud_hidden_folders"
const val WAS_PCLOUD_HIDE_FOLDER_TOOLTIP_SHOWN = "was_pcloud_hide_folder_tooltip_shown"
const val PCLOUD_LAST_FULL_SCAN_AT = "pcloud_last_full_scan_at"
// where the diff sync left off, see PCloudScanner.sync(). 0 means the next sync is a full scan
const val PCLOUD_DIFF_ID = "pcloud_diff_id"

// the choices offered for the interval, in minutes. 0 means no minimum interval at all
val PCLOUD_RESCAN_INTERVAL_CHOICES = arrayListOf(0, 5, 15, 60, 360, 1440)

const val AVOID_SHOWING_ALL_FILES_PROMPT = "avoid_showing_all_files_prompt"
const val SEARCH_ALL_FILES_BY_DEFAULT = "search_all_files_by_default"
const val LAST_EXPORTED_FAVORITES_FOLDER = "last_exported_favorites_folder"
const val SHOW_PERMISSION_RATIONALE = "show_permission_rationale"

// slideshow
const val SLIDESHOW_INTERVAL = "slideshow_interval"
const val SLIDESHOW_INCLUDE_VIDEOS = "slideshow_include_videos"
const val SLIDESHOW_INCLUDE_GIFS = "slideshow_include_gifs"
const val SLIDESHOW_RANDOM_ORDER = "slideshow_random_order"
const val SLIDESHOW_MOVE_BACKWARDS = "slideshow_move_backwards"
const val SLIDESHOW_ANIMATION = "slideshow_animation"
const val SLIDESHOW_LOOP = "loop_slideshow"
const val SLIDESHOW_DEFAULT_INTERVAL = 5
const val SLIDESHOW_SLIDE_DURATION = 500L
const val SLIDESHOW_FADE_DURATION = 1500L
const val SLIDESHOW_START_ON_ENTER = "slideshow_start_on_enter"

// slideshow animations
const val SLIDESHOW_ANIMATION_NONE = 0
const val SLIDESHOW_ANIMATION_SLIDE = 1
const val SLIDESHOW_ANIMATION_FADE = 2

const val RECYCLE_BIN = "recycle_bin"
const val SHOW_FAVORITES = "show_favorites"
const val SHOW_RECYCLE_BIN = "show_recycle_bin"
const val IS_IN_RECYCLE_BIN = "is_in_recycle_bin"
const val SHOW_NEXT_ITEM = "show_next_item"
const val SHOW_PREV_ITEM = "show_prev_item"
const val GO_TO_NEXT_ITEM = "go_to_next_item"
const val GO_TO_PREV_ITEM = "go_to_prev_item"
const val MAX_COLUMN_COUNT = 20
const val SHOW_TEMP_HIDDEN_DURATION = 300000L
const val CLICK_MAX_DURATION = 150
const val CLICK_MAX_DISTANCE = 100
const val MAX_CLOSE_DOWN_GESTURE_DURATION = 300
const val MAX_ZOOM_EQUALITY_TOLERANCE = 0.01
const val DRAG_THRESHOLD = 8
const val MONTH_MILLISECONDS = MONTH_SECONDS * 1000L
const val MIN_SKIP_LENGTH = 2000
const val HIDE_SYSTEM_UI_DELAY = 500L
const val MAX_PRINT_SIDE_SIZE = 4096
const val FAST_FORWARD_VIDEO_MS = 10000

const val EXOPLAYER_MIN_BUFFER_MS = 2000
const val EXOPLAYER_MAX_BUFFER_MS = 8000

const val DIRECTORY = "directory"
const val MEDIUM = "medium"
const val PATH = "path"
const val GET_IMAGE_INTENT = "get_image_intent"
const val GET_VIDEO_INTENT = "get_video_intent"
const val GET_ANY_INTENT = "get_any_intent"
const val SET_WALLPAPER_INTENT = "set_wallpaper_intent"
const val IS_VIEW_INTENT = "is_view_intent"
const val PICKED_PATHS = "picked_paths"
const val SHOULD_INIT_FRAGMENT = "should_init_fragment"
const val PORTRAIT_PATH = "portrait_path"
const val SKIP_AUTHENTICATION = "skip_authentication"

// editor
const val TEMP_FOLDER_NAME = "images"

// rotations
const val ROTATE_BY_SYSTEM_SETTING = 0
const val ROTATE_BY_DEVICE_ROTATION = 1
const val ROTATE_BY_ASPECT_RATIO = 2

// video player type
const val VIDEO_PLAYER_APP = 0
const val VIDEO_PLAYER_SYSTEM = 1

// file loading priority
const val PRIORITY_SPEED = 0
const val PRIORITY_COMPROMISE = 1
const val PRIORITY_VALIDITY = 2

// extended details values
const val EXT_NAME = 1
const val EXT_PATH = 2
const val EXT_SIZE = 4
const val EXT_RESOLUTION = 8
const val EXT_LAST_MODIFIED = 16
const val EXT_DATE_TAKEN = 32
const val EXT_CAMERA_MODEL = 64
const val EXT_EXIF_PROPERTIES = 128
const val EXT_DURATION = 256
const val EXT_ARTIST = 512
const val EXT_ALBUM = 1024
const val EXT_GPS = 2048

// media types
const val TYPE_IMAGES = 1
const val TYPE_VIDEOS = 2
const val TYPE_GIFS = 4
const val TYPE_RAWS = 8
const val TYPE_SVGS = 16
const val TYPE_PORTRAITS = 32

fun getDefaultFileFilter() = TYPE_IMAGES or TYPE_VIDEOS or TYPE_GIFS or TYPE_RAWS or TYPE_SVGS

const val LOCATION_INTERNAL = 1
const val LOCATION_SD = 2
const val LOCATION_OTG = 3
const val LOCATION_PCLOUD = 4
const val LOCATION_SMB = 5

// which storage the folder list is showing. STORAGE_FILTER_ALL keeps meaning every storage
// there is, so a new one is added before it and the stored value of ALL never moves
const val STORAGE_FILTER_LOCAL = 1
const val STORAGE_FILTER_PCLOUD = 2
const val STORAGE_FILTER_ALL = 3
const val STORAGE_FILTER_SMB = 4

const val GROUP_BY_NONE = 1
const val GROUP_BY_LAST_MODIFIED_DAILY = 2
const val GROUP_BY_DATE_TAKEN_DAILY = 4
const val GROUP_BY_FILE_TYPE = 8
const val GROUP_BY_EXTENSION = 16
const val GROUP_BY_FOLDER = 32
const val GROUP_BY_LAST_MODIFIED_MONTHLY = 64
const val GROUP_BY_DATE_TAKEN_MONTHLY = 128
const val GROUP_DESCENDING = 1024
const val GROUP_SHOW_FILE_COUNT = 2048

// bottom actions
const val BOTTOM_ACTION_TOGGLE_FAVORITE = 1
const val BOTTOM_ACTION_EDIT = 2
const val BOTTOM_ACTION_SHARE = 4
const val BOTTOM_ACTION_DELETE = 8
const val BOTTOM_ACTION_ROTATE = 16
const val BOTTOM_ACTION_PROPERTIES = 32
const val BOTTOM_ACTION_CHANGE_ORIENTATION = 64
const val BOTTOM_ACTION_SLIDESHOW = 128
const val BOTTOM_ACTION_SHOW_ON_MAP = 256
const val BOTTOM_ACTION_TOGGLE_VISIBILITY = 512
const val BOTTOM_ACTION_RENAME = 1024
const val BOTTOM_ACTION_SET_AS = 2048
const val BOTTOM_ACTION_COPY = 4096
const val BOTTOM_ACTION_MOVE = 8192
const val BOTTOM_ACTION_RESIZE = 16384

const val DEFAULT_BOTTOM_ACTIONS = BOTTOM_ACTION_TOGGLE_FAVORITE or BOTTOM_ACTION_EDIT or BOTTOM_ACTION_SHARE or BOTTOM_ACTION_DELETE

// aspect ratios used at the editor for cropping
const val ASPECT_RATIO_FREE = 0
const val ASPECT_RATIO_ONE_ONE = 1
const val ASPECT_RATIO_FOUR_THREE = 2
const val ASPECT_RATIO_SIXTEEN_NINE = 3
const val ASPECT_RATIO_OTHER = 4

// constants related to image quality
const val LOW_TILE_DPI = 160
const val NORMAL_TILE_DPI = 220
const val WEIRD_TILE_DPI = 240
const val HIGH_TILE_DPI = 280

const val ROUNDED_CORNERS_NONE = 1
const val ROUNDED_CORNERS_SMALL = 2
const val ROUNDED_CORNERS_BIG = 3

const val FOLDER_MEDIA_CNT_LINE = 1
const val FOLDER_MEDIA_CNT_BRACKETS = 2
const val FOLDER_MEDIA_CNT_NONE = 3

const val FOLDER_STYLE_SQUARE = 1
const val FOLDER_STYLE_ROUNDED_CORNERS = 2

// animations
const val THUMBNAIL_FADE_DURATION_MS = 150

fun getPermissionToRequest() = if (isTiramisuPlus()) PERMISSION_READ_MEDIA_IMAGES else PERMISSION_WRITE_STORAGE

fun getPermissionsToRequest(): Collection<Int> {
    val permissions = mutableListOf(getPermissionToRequest())
    if (isRPlus()) {
        permissions.add(PERMISSION_MEDIA_LOCATION)
    }

    if (isTiramisuPlus()) {
        permissions.add(PERMISSION_READ_MEDIA_VIDEO)
    }

    return permissions
}
