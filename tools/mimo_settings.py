#!/usr/bin/env python3
"""Ledger of Mimo SET/GET (0x02/0x8E) and shooting-mode writes (0x02/0xE1).

Usage:
    python3 tools/mimo_settings.py captures/mimo-settings-1.pcapng
"""
from __future__ import annotations

import collections
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from duml_parse import CAMERA_IP, cmd, scan_frames
from live_view_tools import read_packets

MODE = {
    0x00: "SlowMo",
    0x01: "Video",
    0x02: "TimeLapse",
    0x05: "Photo(Nano)",
    0x0A: "HyperLapse",
    0x1A: "Live",
    0x28: "SuperNight",
    0x3F: "PanoPhoto",
}


def parse_8e(pl: bytes):
    if len(pl) < 4:
        return None
    if pl[0] == 0x00 and pl[1] == 0x01:
        pid = pl[2] | (pl[3] << 8)
        return ("GET", pid, b"")
    if pl[0] == 0x01 and pl[1] == 0x01 and len(pl) >= 5:
        pid = pl[2] | (pl[3] << 8)
        ln = pl[4]
        return ("SET", pid, pl[5:5 + ln])
    return ("?", 0, pl)


def main(path: str):
    packets = read_packets(path)
    e1 = []
    rec = []
    photo = []
    p8e = []
    other = []
    pkt_i = 0
    for proto, src, sport, dst, dport, data in packets:
        pkt_i += 1
        to_cam = dst == CAMERA_IP
        for fr in scan_frames(data):
            key = (fr["set"], fr["cmd"])
            pl = bytes(fr["payload"])
            row = (pkt_i, to_cam, fr["flags"], pl, fr["sender"], fr["receiver"])
            if key == (0x02, 0xE1):
                e1.append(row)
            elif key == (0x02, 0x02):
                rec.append(row)
            elif key == (0x02, 0x01):
                photo.append(row)
            elif key == (0x02, 0x8E):
                p8e.append(row)
            elif to_cam and fr["flags"] == 0x40 and key[0] == 0x02 and key[1] not in {
                0x80, 0x82, 0xDC, 0x0C, 0xA0, 0x61, 0xFF,
            }:
                other.append((pkt_i, key, to_cam, fr["flags"], pl.hex()))

    print(f"# {path}")
    print(f"packets_with_l4={pkt_i}")

    print("\n## 0x02/0xE1 shooting mode")
    if not e1:
        print("  (none — switch Photo/Video in Mimo)")
    for pkt_i, to_cam, flags, pl, snd, rcv in e1:
        label = ""
        if to_cam and pl:
            label = MODE.get(pl[0], f"unknown 0x{pl[0]:02x}")
        print(f"  pkt#{pkt_i} {'->cam' if to_cam else '<-cam'} "
              f"flags=0x{flags:02x} {pl.hex() or '(empty)'} {label}")

    print("\n## 0x02/0x02 record")
    for pkt_i, to_cam, flags, pl, snd, rcv in rec:
        print(f"  pkt#{pkt_i} {'->cam' if to_cam else '<-cam'} "
              f"flags=0x{flags:02x} {pl.hex()}")

    print("\n## 0x02/0x01 photo shutter")
    for pkt_i, to_cam, flags, pl, snd, rcv in photo:
        print(f"  pkt#{pkt_i} {'->cam' if to_cam else '<-cam'} "
              f"flags=0x{flags:02x} {pl.hex()}")

    print("\n## 0x02/0x8E GET/SET")
    sets = collections.Counter()
    gets = collections.Counter()
    if not p8e:
        print("  (none)")
    for pkt_i, to_cam, flags, pl, snd, rcv in p8e:
        parsed = parse_8e(pl)
        if parsed and to_cam and flags == 0x40:
            kind, pid, val = parsed
            if kind == "SET":
                sets[pid] += 1
                print(f"  pkt#{pkt_i} SET pid=0x{pid:04x} value={val.hex()}")
            elif kind == "GET":
                gets[pid] += 1
        elif not to_cam and parsed:
            kind, pid, val = parsed
            if kind == "SET" or (to_cam is False and flags == 0xC0):
                print(f"  pkt#{pkt_i} <-cam 0x8E flags=0x{flags:02x} {pl.hex()[:80]}")

    print("\n## 0x8E pid counts (app → camera, flags 0x40)")
    print("  SET:", {f"0x{p:04x}": n for p, n in sets.most_common()})
    print("  GET:", {f"0x{p:04x}": n for p, n in gets.most_common(20)})

    print("\n## Other app→camera 0x02 requests")
    if not other:
        print("  (none)")
    for pkt_i, key, to_cam, flags, hx in other[:60]:
        print(f"  pkt#{pkt_i} {cmd(*key)} {hx[:80]}")
    if len(other) > 60:
        print(f"  ... +{len(other)-60} more")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
