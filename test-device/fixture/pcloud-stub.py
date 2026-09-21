#!/usr/bin/env python3
"""A stand-in for the pCloud HTTP API, big enough for the transfers this app makes.

Why a stub and not an account: the driving scripts here are built so that a run touches nothing
personal and needs nothing secret. A throwaway pCloud account would mean a token living outside
the repository, a network the run depends on, and an account whose state drifts between runs --
and the thing under test is the app's path from a share to pCloud, not pCloud itself.

What it serves is the slice PCloudApi calls: userinfo, diff, listfolder, uploadfile, getthumb,
getfilelink and the download link that one hands out. Everything is backed by a directory on this
machine, so a test can look at what arrived with plain `stat`, the way it looks at the share.

Plain http, on purpose. An https stub would need a certificate the app is built to trust, and a
debug build carrying one out of this repository would be worth more to an attacker than this test
is worth to us; pCloudUrl() lets a host carry its own scheme for exactly this, and the debug
build's network config allows cleartext to 10.0.2.2 and nowhere else.

Every request is appended to a log file, so a script can assert on what the app asked for -- that
an upload said nopartial, say, or that it carried the token.
"""
import argparse
import http.server
import json
import os
import re
import shutil
import socketserver
import sys
import threading
import time
import zlib
from datetime import datetime, timezone
from urllib.parse import urlparse, parse_qs

# pCloud's own result codes, the two the app reads by number
RESULT_LOG_IN_FAILED = 2000
RESULT_INVALID_REQUEST = 1000

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp")
VIDEO_EXTENSIONS = (".mp4", ".mkv", ".webm", ".avi", ".mov", ".3gp")

# pCloud's own categories; the app classifies by filename first and falls back to these
CATEGORY_IMAGE = 1
CATEGORY_VIDEO = 2


class Account:
    """The tree, the ids, and the diff events -- everything the API answers from.

    Ids are handed out on demand and kept for the life of the process: the app stores them in its
    cache after a scan and then names folders by id, so an id that moved between two requests
    would look to it like a folder that vanished.
    """

    def __init__(self, root, log_path):
        self.root = os.path.abspath(root)
        self.log_path = log_path
        self.lock = threading.Lock()
        self.ids = {"": 0}
        self.next_id = 1000
        # the id the app is told about before a full scan; it has to be above zero or the app
        # keeps falling back to a full listing instead of asking for the changes
        self.diff_id = 1
        self.events = []

    def item_id(self, relative):
        with self.lock:
            if relative not in self.ids:
                self.ids[relative] = self.next_id
                self.next_id += 1
            return self.ids[relative]

    def path_of_id(self, wanted):
        if wanted == 0:
            return ""
        with self.lock:
            for relative, assigned in self.ids.items():
                if assigned == wanted:
                    return relative
        return None

    def absolute(self, relative):
        return os.path.join(self.root, relative) if relative else self.root

    def add_event(self, event, metadata):
        with self.lock:
            self.diff_id += 1
            self.events.append({"diffid": self.diff_id, "event": event, "metadata": metadata})
            return self.diff_id

    def log(self, line):
        with open(self.log_path, "a", encoding="utf-8") as log:
            log.write(line + "\n")


def modified_of(path):
    """The date format pCloud sends, which the scanner parses with a fixed English pattern."""
    stamp = datetime.fromtimestamp(os.path.getmtime(path), timezone.utc)
    return stamp.strftime("%a, %d %b %Y %H:%M:%S +0000")


def content_hash(path):
    """Any stable number will do: the app only ever compares it for equality."""
    with open(path, "rb") as handle:
        return zlib.crc32(handle.read()) & 0xFFFFFFFF


def category_of(name):
    lowered = name.lower()
    if lowered.endswith(IMAGE_EXTENSIONS):
        return CATEGORY_IMAGE
    if lowered.endswith(VIDEO_EXTENSIONS):
        return CATEGORY_VIDEO
    return 0


def file_metadata(account, relative):
    absolute = account.absolute(relative)
    name = os.path.basename(relative)
    parent = os.path.dirname(relative)
    return {
        "name": name,
        "isfolder": False,
        "fileid": account.item_id(relative),
        "parentfolderid": account.item_id(parent),
        "modified": modified_of(absolute),
        "size": os.path.getsize(absolute),
        "category": category_of(name),
        "hash": content_hash(absolute),
        "thumb": category_of(name) != 0,
    }


def folder_metadata(account, relative, recursive):
    absolute = account.absolute(relative)
    name = os.path.basename(relative) if relative else "/"
    parent = os.path.dirname(relative)
    metadata = {
        "name": name,
        "isfolder": True,
        "folderid": account.item_id(relative),
        "parentfolderid": account.item_id(parent) if relative else 0,
        "modified": modified_of(absolute),
        "contents": [],
    }

    for entry in sorted(os.listdir(absolute)):
        child = os.path.join(relative, entry) if relative else entry
        if os.path.isdir(os.path.join(absolute, entry)):
            # a folder is always described, recursive or not; without the recursion the app
            # walks into it with a listfolder of its own
            metadata["contents"].append(
                folder_metadata(account, child, recursive) if recursive else folder_metadata(account, child, False)
            )
        else:
            metadata["contents"].append(file_metadata(account, child))

    return metadata


def available_name(folder, name):
    """What pCloud calls an upload whose name is taken, with renameifexists on: "name (1).ext"."""
    if not os.path.exists(os.path.join(folder, name)):
        return name

    stem, extension = os.path.splitext(name)
    index = 1
    while True:
        candidate = "%s (%d)%s" % (stem, index, extension)
        if not os.path.exists(os.path.join(folder, candidate)):
            return candidate
        index += 1


def split_multipart(body, boundary):
    """The one file out of a multipart body, as (filename, bytes).

    Written out rather than taken from a library because the standard one for this was dropped in
    3.13 and every replacement is a dependency this directory would have to carry.
    """
    marker = b"--" + boundary
    for part in body.split(marker):
        if not part.strip(b"-\r\n"):
            continue

        header_block, _, content = part.partition(b"\r\n\r\n")
        if not content:
            continue

        match = re.search(br'filename="([^"]*)"', header_block)
        if not match:
            continue

        return match.group(1).decode("utf-8"), content.rstrip(b"\r\n")

    return None, None


class Handler(http.server.BaseHTTPRequestHandler):
    account = None
    token = None
    public_host = None

    def log_message(self, fmt, *args):
        # the server's own line goes to the run's log file rather than to the test's output,
        # where it would bury what the script is saying
        self.account.log("%s %s" % (self.command, self.path))

    # every answer pCloud gives is JSON with a result field, including its refusals
    def send_json(self, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_refusal(self, result, error):
        self.send_json({"result": result, "error": error})

    def authorized(self):
        header = self.headers.get("Authorization", "")
        if header == "Bearer " + self.token:
            return True

        self.account.log("REFUSED (no token) %s %s" % (self.command, self.path))
        self.send_refusal(RESULT_LOG_IN_FAILED, "Log in failed")
        return False

    def do_GET(self):
        parsed = urlparse(self.path)
        method = parsed.path.strip("/")
        params = {key: values[0] for key, values in parse_qs(parsed.query).items()}
        self.account.log("GET %s %s" % (method, json.dumps(params, sort_keys=True)))

        if method.startswith("dl/"):
            self.serve_download(method[len("dl/"):])
            return

        if not self.authorized():
            return

        if method == "userinfo":
            self.send_json({"result": 0, "email": "fixture@example.invalid", "userid": 1})
        elif method == "diff":
            self.serve_diff(params)
        elif method == "listfolder":
            self.serve_listfolder(params)
        elif method == "getthumb":
            self.serve_thumb(params)
        elif method == "getfilelink":
            self.serve_file_link(params)
        else:
            self.send_refusal(RESULT_INVALID_REQUEST, "the stub does not answer %s" % method)

    def do_POST(self):
        parsed = urlparse(self.path)
        method = parsed.path.strip("/")
        params = {key: values[0] for key, values in parse_qs(parsed.query).items()}
        self.account.log("POST %s %s" % (method, json.dumps(params, sort_keys=True)))

        if not self.authorized():
            return

        if method == "uploadfile":
            self.serve_upload(params)
        else:
            self.send_refusal(RESULT_INVALID_REQUEST, "the stub does not answer %s" % method)

    def serve_diff(self, params):
        # with last=1 the app is only after the current id, before a full listing
        if "last" in params and "diffid" not in params:
            self.send_json({"result": 0, "diffid": self.account.diff_id, "entries": []})
            return

        since = int(params.get("diffid", "0"))
        entries = [event for event in self.account.events if event["diffid"] > since]
        self.send_json({"result": 0, "diffid": self.account.diff_id, "entries": entries})

    def serve_listfolder(self, params):
        relative = params.get("path", "/").strip("/")
        absolute = self.account.absolute(relative)
        if not os.path.isdir(absolute):
            self.send_refusal(2005, "Directory does not exist.")
            return

        recursive = params.get("recursive", "0") == "1"
        self.send_json({"result": 0, "metadata": folder_metadata(self.account, relative, recursive)})

    def serve_upload(self, params):
        folder = self.account.path_of_id(int(params.get("folderid", "0")))
        if folder is None:
            self.send_refusal(2005, "Directory does not exist.")
            return

        content_type = self.headers.get("Content-Type", "")
        match = re.search(r"boundary=(.+)$", content_type)
        if not match:
            self.send_refusal(RESULT_INVALID_REQUEST, "not a multipart upload")
            return

        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        sent_name, content = split_multipart(body, match.group(1).strip('"').encode("utf-8"))
        if content is None:
            self.send_refusal(RESULT_INVALID_REQUEST, "the multipart body carried no file")
            return

        name = params.get("filename", sent_name)
        destination = self.account.absolute(folder)
        if params.get("renameifexists", "1") == "1":
            name = available_name(destination, name)

        # nopartial means the file must not appear until the whole of it is there, which is what
        # writing beside it and moving it into place gives
        partial = os.path.join(destination, "." + name + ".part")
        with open(partial, "wb") as handle:
            handle.write(content)

        written = os.path.join(destination, name)
        shutil.move(partial, written)

        modified = int(params.get("mtime", str(int(time.time()))))
        os.utime(written, (modified, modified))

        relative = os.path.join(folder, name) if folder else name
        metadata = file_metadata(self.account, relative)
        self.account.add_event("createfile", metadata)
        self.account.log("UPLOADED %s" % relative)
        self.send_json({"result": 0, "metadata": [metadata]})

    def serve_thumb(self, params):
        relative = self.account.path_of_id(int(params.get("fileid", "0")))
        if relative is None or not os.path.isfile(self.account.absolute(relative)):
            self.send_refusal(2009, "File not found.")
            return

        # the picture itself, at its own size. What the app does with it is draw it into a tile,
        # and a tile of the fixture's test patterns is as readable at one size as another
        with open(self.account.absolute(relative), "rb") as handle:
            body = handle.read()

        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def serve_file_link(self, params):
        relative = self.account.path_of_id(int(params.get("fileid", "0")))
        if relative is None:
            self.send_refusal(2009, "File not found.")
            return

        # the host carries its own scheme, which is what the app allows for a stub; a link is
        # fetched without the token, so the id in the path is all the authorisation there is
        self.send_json({"result": 0, "hosts": [self.public_host], "path": "/dl/%d" % self.account.item_id(relative)})

    def serve_download(self, raw_id):
        relative = self.account.path_of_id(int(raw_id))
        if relative is None:
            self.send_error(404)
            return

        with open(self.account.absolute(relative), "rb") as handle:
            body = handle.read()

        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main():
    parser = argparse.ArgumentParser(description="a stand-in for the pCloud API, backed by a directory")
    parser.add_argument("--root", required=True, help="the directory that stands for the account's root")
    parser.add_argument("--port", type=int, default=8089)
    parser.add_argument("--token", default="fixture-token")
    parser.add_argument("--log", required=True, help="where every request is appended")
    parser.add_argument("--public-host", default=None, help="how the device reaches this server, for link answers")
    arguments = parser.parse_args()

    os.makedirs(arguments.root, exist_ok=True)
    Handler.account = Account(arguments.root, arguments.log)
    Handler.token = arguments.token
    Handler.public_host = arguments.public_host or "http://10.0.2.2:%d" % arguments.port

    server = Server(("0.0.0.0", arguments.port), Handler)
    print("pCloud stub serving %s on port %d" % (arguments.root, arguments.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass

    return 0


if __name__ == "__main__":
    sys.exit(main())
