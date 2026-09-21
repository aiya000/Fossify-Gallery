#!/usr/bin/env python3
"""Where to tap, read off a uiautomator dump.

Prints "<x> <y>" -- the centre of the first node whose text, content description or resource id
matches -- and exits 1 when nothing matches, so a caller can tell "not there yet" from "there".
"""

import argparse
import sys
import xml.etree.ElementTree as ElementTree


def centre_of(node):
    bounds = node.get("bounds", "")
    # "[left,top][right,bottom]"
    try:
        first, second = bounds.split("][")
        left, top = (int(n) for n in first.lstrip("[").split(","))
        right, bottom = (int(n) for n in second.rstrip("]").split(","))
    except ValueError:
        return None

    return (left + right) // 2, (top + bottom) // 2


def matches(node, text, resource_id, exact):
    if resource_id is not None:
        return node.get("resource-id", "").endswith(resource_id)

    haystacks = (node.get("text", ""), node.get("content-desc", ""))
    if exact:
        return any(value == text for value in haystacks)
    return any(text.lower() in value.lower() for value in haystacks if value)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dump")
    parser.add_argument("--text")
    parser.add_argument("--resource-id")
    parser.add_argument("--exact", action="store_true")
    parser.add_argument(
        "--list",
        action="store_true",
        help="print every text on screen instead, which is what to reach for when a tap cannot land",
    )
    args = parser.parse_args()

    tree = ElementTree.parse(args.dump)

    if args.list:
        for node in tree.iter("node"):
            label = node.get("text") or node.get("content-desc")
            if label:
                print(f"{label}\t{node.get('resource-id', '')}\t{node.get('bounds', '')}")
        return 0

    if args.text is None and args.resource_id is None:
        parser.error("one of --text, --resource-id or --list is needed")

    for node in tree.iter("node"):
        if matches(node, args.text, args.resource_id, args.exact):
            point = centre_of(node)
            if point is not None:
                print(f"{point[0]} {point[1]}")
                return 0

    return 1


if __name__ == "__main__":
    sys.exit(main())
