#!/usr/bin/env bash
# Builds the fixture share from nothing: the folders of manifest.env, filled with generated
# videos and images of known length and known count.
#
# Idempotent, and it never deletes anything: a file that is already there is left alone, so a
# second run costs nothing. To start over, clear the share directory by hand -- a script that
# deletes a directory read from a variable is not something to have lying around.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$here/.."
# shellcheck source=manifest.env
source fixture/manifest.env

if ! command -v ffmpeg > /dev/null; then
    echo "ffmpeg is needed to generate the fixture media" >&2
    exit 1
fi

mkdir -p "$FIXTURE_SHARE_DIR"
echo "seeding $FIXTURE_SHARE_DIR"

# a video of a known length, with a visible counter in it, so that what is on screen during
# playback says which file it is and how far in it is
make_video() {
    local path="$1"
    local index="$2"
    [ -f "$path" ] && return 0
    ffmpeg -loglevel error -y \
        -f lavfi -i "testsrc=size=320x240:rate=15:duration=$FIXTURE_VIDEO_SECONDS" \
        -f lavfi -i "sine=frequency=$((220 + index * 110)):duration=$FIXTURE_VIDEO_SECONDS" \
        -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$path"
}

make_image() {
    local path="$1"
    [ -f "$path" ] && return 0
    ffmpeg -loglevel error -y -f lavfi -i "testsrc=size=320x240:rate=1:duration=1" -frames:v 1 "$path"
}

videos=0
images=0
for entry in "${FIXTURE_TREE[@]}"; do
    folder="${entry%%:*}"
    rest="${entry#*:}"
    video_count="${rest%%:*}"
    image_count="${rest##*:}"

    mkdir -p "$FIXTURE_SHARE_DIR/$folder"
    for i in $(seq 1 "$video_count"); do
        make_video "$FIXTURE_SHARE_DIR/$folder/video-$i.mp4" "$i"
        videos=$((videos + 1))
    done
    for i in $(seq 1 "$image_count"); do
        make_image "$FIXTURE_SHARE_DIR/$folder/image-$i.jpg"
        images=$((images + 1))
    done
done

# The renders. Noise rather than a pattern, so that the file is as large as its dimensions say;
# ffmpeg picks the encoder off the extension, so the same command writes the JPEG and the PNGs,
# and the name is whatever the manifest calls it -- Japanese included, which is what a share of
# somebody's own files is named like
make_render() {
    local path="$1"
    local side="$2"
    [ -f "$path" ] && return 0
    ffmpeg -loglevel error -y \
        -f lavfi -i "nullsrc=size=${side}x${side},geq=random(1)*255:random(2)*255:random(3)*255" \
        -frames:v 1 "$path"
}

mkdir -p "$FIXTURE_SHARE_DIR/$FIXTURE_RENDERS_FOLDER"
for entry in "${FIXTURE_RENDERS[@]}"; do
    name="${entry%:*}"
    make_render "$FIXTURE_SHARE_DIR/$FIXTURE_RENDERS_FOLDER/$name" "${entry##*:}"
    images=$((images + 1))
done

# the filler. One video each, copied rather than generated: what they are for is the number of
# folders the walk has to get through, not what is in them
if [ "$FIXTURE_FILLER_FOLDERS" -gt 0 ]; then
    echo "filling $FIXTURE_FILLER_FOLDERS more folders"
    source_video="$FIXTURE_SHARE_DIR/Camera/video-1.mp4"
    for i in $(seq 1 "$FIXTURE_FILLER_FOLDERS"); do
        # named to sort after every folder the tests look for: the folder list draws 400 rows
        # of filler, and anything sorting after them would be pushed off the screen
        folder="$(printf '%s/Filler/zz%04d' "$FIXTURE_SHARE_DIR" "$i")"
        mkdir -p "$folder"
        [ -f "$folder/video.mp4" ] || cp "$source_video" "$folder/video.mp4"
        videos=$((videos + 1))
    done
fi

echo "$videos videos, $images images in $((${#FIXTURE_TREE[@]} + 1 + FIXTURE_FILLER_FOLDERS)) folders"

# The renders are only worth having if they still land either side of the mark. ffmpeg's encoder
# settling on a better compression would quietly leave every one of them under it, and the case
# that checks the large ones draw would then be checking nothing at all
for entry in "${FIXTURE_RENDERS[@]}"; do
    name="${entry%:*}"
    bytes="$(stat -c %s "$FIXTURE_SHARE_DIR/$FIXTURE_RENDERS_FOLDER/$name")"
    if [ "$name" = "$FIXTURE_RENDERS_UNDER_THE_MARK" ]; then
        if [ "$bytes" -gt "$FIXTURE_REWINDABLE_BYTES" ]; then
            echo "$name is $bytes bytes, which is over the $FIXTURE_REWINDABLE_BYTES mark it is meant to stay under" >&2
            exit 1
        fi
    elif [ "$bytes" -le "$FIXTURE_REWINDABLE_BYTES" ]; then
        echo "$name is $bytes bytes, which is under the $FIXTURE_REWINDABLE_BYTES mark it is meant to be over" >&2
        exit 1
    fi
done

# the manifest is what the driving scripts assert on, so a mismatch here has to be loud: it would
# otherwise turn into a failing test that looks like a bug in the app
if [ "$videos" -ne "$FIXTURE_VIDEOS" ] || [ "$images" -ne "$FIXTURE_IMAGES" ]; then
    echo "the tree does not add up to the counts in manifest.env ($FIXTURE_VIDEOS videos, $FIXTURE_IMAGES images)" >&2
    exit 1
fi
