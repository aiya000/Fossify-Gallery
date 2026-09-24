#!/usr/bin/env python3
"""Where to tap, read off a uiautomator dump.

Prints "<x> <y>" -- the centre of the first node whose text, content description or resource id
matches -- and exits 1 when nothing matches, so a caller can tell "not there yet" from "there".
"""

import argparse
import sys
import xml.etree.ElementTree as ElementTree


def box_of(node):
    bounds = node.get("bounds", "")
    # "[left,top][right,bottom]"
    try:
        first, second = bounds.split("][")
        left, top = (int(n) for n in first.lstrip("[").split(","))
        right, bottom = (int(n) for n in second.rstrip("]").split(","))
    except ValueError:
        return None

    return left, top, right, bottom


def centre_of(node):
    box = box_of(node)
    if box is None:
        return None

    left, top, right, bottom = box
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
        "--last",
        action="store_true",
        help=(
            "take the last match rather than the first. The selection mode's toolbar is drawn over "
            "the ordinary one and both are in the tree, so the three dots that belong to the "
            "selection are the second of the two"
        ),
    )
    parser.add_argument(
        "--bounds",
        action="store_true",
        help=(
            "print '<left> <top> <right> <bottom>' instead of a point, which is what a check on "
            "the pixels of one view wants -- thumbs.py --region takes exactly these four numbers"
        ),
    )
    parser.add_argument(
        "--list",
        action="store_true",
        help="print every text on screen instead, which is what to reach for when a tap cannot land",
    )
    parser.add_argument(
        "--checked",
        action="store_true",
        help=(
            "print the label of the checked node instead of a point, which is how a script reads "
            "which row of a radio dialog is the selected one rather than only which rows exist"
        ),
    )
    parser.add_argument(
        "--value",
        action="store_true",
        help=(
            "print the text of the matched node instead of a point, which is how a script reads "
            "what a text box opened with -- a check that only looked for the wanted text would "
            "say nothing about what was there instead"
        ),
    )
    args = parser.parse_args()

    tree = ElementTree.parse(args.dump)

    if args.list:
        for node in tree.iter("node"):
            label = node.get("text") or node.get("content-desc")
            if label:
                print(f"{label}\t{node.get('resource-id', '')}\t{node.get('bounds', '')}")
        return 0

    # Which row of a radio dialog carries the mark. A script that only listed the rows would pass
    # whatever the dialog had selected, so the mark is read rather than the presence of the row
    if args.checked:
        for node in tree.iter("node"):
            if node.get("checked") == "true":
                label = node.get("text") or node.get("content-desc")
                if label:
                    print(label)
                    return 0
        return 1

    if args.text is None and args.resource_id is None:
        parser.error("one of --text, --resource-id, --list or --checked is needed")

    if args.value:
        for node in tree.iter("node"):
            if matches(node, args.text, args.resource_id, args.exact):
                print(node.get("text", ""))
                return 0
        return 1

    read = box_of if args.bounds else centre_of
    found = None
    for node in tree.iter("node"):
        if matches(node, args.text, args.resource_id, args.exact):
            value = read(node)
            if value is not None:
                found = value
                if not args.last:
                    break

    if found is None:
        return 1

    print(" ".join(str(number) for number in found))
    return 0


if __name__ == "__main__":
    sys.exit(main())
