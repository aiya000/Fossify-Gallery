#!/usr/bin/env python3
"""Which tiles of the media grid actually have a picture in them.

A thumbnail that never arrived is not missing from the view tree -- the tile is there, drawn in
its placeholder, and `uiautomator dump` says the same thing either way. The only place the
difference shows is the pixels, so this reads them off a screenshot: a tile with a picture in it
is many colours, and a tile without one is mostly the placeholder's single colour, whether it is
bare or has the adapter's warning icon sitting on it.

Prints "<name> drawn" or "<name> blank" per tile, and exits 1 when any is blank. It wants the
filenames turned on in the grid (the toolbar's "Toggle filename visibility"), because a tile that
failed is only worth reporting if it can be named.

The fixture's media are test patterns and noise on purpose. A photograph of a clear sky is one
flat colour too, and would be called blank here -- which is fine for a fixture nobody points at
their own share.
"""

import argparse
import sys
import xml.etree.ElementTree as ElementTree

from PIL import Image

# How much of a tile one single colour is allowed to be before the tile counts as showing no
# picture. A placeholder is that colour edge to edge; a placeholder with the adapter's warning
# icon on it is still nine tenths of it, which is why this is not a measure of contrast -- a tile
# that says "this one failed" has plenty of contrast and still has no thumbnail in it
DOMINANT = 0.6

# the filename is drawn over the bottom of the tile, on a strip darkened to keep it readable.
# That strip is a picture of its own as far as the numbers go, so it is left out
NAME_STRIP = 0.25

# and so is a margin all the way round. A tile's bounds take in the line the grid draws between
# tiles, and against a light background that line alone is contrast enough to make an empty tile
# look like a picture
INSET = 0.05


def bounds_of(node):
    first, second = node.get("bounds", "").split("][")
    left, top = (int(n) for n in first.lstrip("[").split(","))
    right, bottom = (int(n) for n in second.rstrip("]").split(","))
    return left, top, right, bottom


def inside(outer, inner):
    return outer[0] <= inner[0] and outer[1] <= inner[1] and outer[2] >= inner[2] and outer[3] >= inner[3]


def tiles_of(dump):
    """Every media tile on screen, as (name, bounds), in the order the grid draws them."""
    tree = ElementTree.parse(dump)
    holders = []
    names = []
    for node in tree.iter("node"):
        resource_id = node.get("resource-id", "")
        if resource_id.endswith("media_item_holder"):
            holders.append(bounds_of(node))
        elif resource_id.endswith("medium_name"):
            names.append((node.get("text", ""), bounds_of(node)))

    found = []
    for holder in holders:
        name = next((text for text, at in names if inside(holder, at)), None)
        if name is not None:
            found.append((name, holder))

    return found


def is_drawn(screenshot, tile):
    left, top, right, bottom = tile
    bottom -= int((bottom - top) * NAME_STRIP)
    inset = int(min(right - left, bottom - top) * INSET)
    left, top, right, bottom = left + inset, top + inset, right - inset, bottom - inset
    if right - left < 8 or bottom - top < 8:
        return False

    # quantised, so that a gradient or the noise of a JPEG does not count as a thousand colours
    # that happen to look the same
    crop = screenshot.crop((left, top, right, bottom)).convert("RGB").quantize(colors=16)
    counts = crop.getcolors() or []
    if not counts:
        return False

    pixels = (right - left) * (bottom - top)
    return max(count for count, _ in counts) < pixels * DOMINANT


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("screenshot")
    parser.add_argument("dump")
    parser.add_argument(
        "--expect",
        type=int,
        help="how many tiles must be on screen; without it, whatever is there is checked",
    )
    args = parser.parse_args()

    tiles = tiles_of(args.dump)
    if not tiles:
        print("no media tile carries a filename; are filenames turned on in the grid?", file=sys.stderr)
        return 1

    screenshot = Image.open(args.screenshot)
    blank = 0
    for name, tile in tiles:
        drawn = is_drawn(screenshot, tile)
        print(f"{name}\t{'drawn' if drawn else 'blank'}")
        if not drawn:
            blank += 1

    if args.expect is not None and len(tiles) != args.expect:
        print(f"{len(tiles)} tiles are on screen, expected {args.expect}", file=sys.stderr)
        return 1

    return 1 if blank else 0


if __name__ == "__main__":
    sys.exit(main())
