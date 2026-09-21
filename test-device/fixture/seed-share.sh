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

echo "$videos videos, $images images in ${#FIXTURE_TREE[@]} folders"

# the manifest is what the driving scripts assert on, so a mismatch here has to be loud: it would
# otherwise turn into a failing test that looks like a bug in the app
if [ "$videos" -ne "$FIXTURE_VIDEOS" ] || [ "$images" -ne "$FIXTURE_IMAGES" ]; then
    echo "the tree does not add up to the counts in manifest.env ($FIXTURE_VIDEOS videos, $FIXTURE_IMAGES images)" >&2
    exit 1
fi
