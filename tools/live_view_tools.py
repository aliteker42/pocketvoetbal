#!/usr/bin/env python3
"""Inventory DUML opcodes + subscribe keys in a Mimo live-view pcap.

Read-only analysis for .planning/LIVE-VIEW-TOOLS.md. Does not send anything.
Reuses the CRC-valid scanner from duml_parse.py.

Usage:
    python3 tools/live_view_tools.py captures/live1.pcap
"""
from __future__ import annotations

import collections
import os
import re
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from duml_parse import CAMERA_IP, KNOWN_COMMANDS, cmd, cmd_name, scan_frames

# live1.pcap is Apple pcapng (truncated). Parse it directly — no scapy required.


def _ipv4_packets_from_ethernet(frame: bytes):
    """Yield (proto, src, sport, dst, dport, payload) from one Ethernet frame."""
    if len(frame) < 14:
        return
    ethertype = int.from_bytes(frame[12:14], "big")
    off = 14
    if ethertype == 0x8100 and len(frame) >= 18:  # VLAN
        ethertype = int.from_bytes(frame[16:18], "big")
        off = 18
    if ethertype != 0x0800 or len(frame) < off + 20:
        return
    ip = frame[off:]
    vihl = ip[0]
    if vihl >> 4 != 4:
        return
    ihl = (vihl & 0x0F) * 4
    total = int.from_bytes(ip[2:4], "big")
    if ihl < 20 or total < ihl or len(ip) < ihl:
        return
    proto = ip[9]
    src = ".".join(str(b) for b in ip[12:16])
    dst = ".".join(str(b) for b in ip[16:20])
    l4 = ip[ihl:total] if total <= len(ip) else ip[ihl:]
    if proto == 17 and len(l4) >= 8:  # UDP
        sport = int.from_bytes(l4[0:2], "big")
        dport = int.from_bytes(l4[2:4], "big")
        ulen = int.from_bytes(l4[4:6], "big")
        payload = l4[8:ulen] if ulen >= 8 else l4[8:]
        yield ("UDP", src, sport, dst, dport, payload)
    elif proto == 6 and len(l4) >= 20:  # TCP
        sport = int.from_bytes(l4[0:2], "big")
        dport = int.from_bytes(l4[2:4], "big")
        doff = (l4[12] >> 4) * 4
        if doff >= 20 and len(l4) > doff:
            payload = l4[doff:]
            if payload:
                yield ("TCP", src, sport, dst, dport, payload)


def read_packets(path: str):
    """Read IPv4 UDP/TCP payloads from pcap or pcapng. Stops cleanly on a truncated tail."""
    with open(path, "rb") as f:
        magic = f.read(4)
        f.seek(0)
        if magic == b"\n\r\r\n":
            return list(_read_pcapng(f))
        if magic in (b"\xd4\xc3\xb2\xa1", b"\xa1\xb2\xc3\xd4"):
            return list(_read_pcap(f, magic))
        raise SystemExit(f"unrecognised capture magic {magic.hex()}")


def _read_pcap(f, magic: bytes):
    le = magic == b"\xd4\xc3\xb2\xa1"
    endian = "<" if le else ">"
    hdr = f.read(24)
    if len(hdr) < 24:
        return
    _magic, _vmaj, _vmin, _zone, _sig, _snap, link = struct.unpack(endian + "IHHIIII", hdr)
    while True:
        ph = f.read(16)
        if len(ph) < 16:
            return
        ts_sec, ts_usec, incl, orig = struct.unpack(endian + "IIII", ph)
        data = f.read(incl)
        if len(data) < incl:
            return
        yield from _link_packets(link, data)


def _read_pcapng(f):
    while True:
        hdr = f.read(8)
        if len(hdr) < 8:
            return
        btype, total = struct.unpack("<II", hdr)
        if total < 12 or total % 4 != 0:
            return  # truncated / corrupt tail (live1.pcap ends this way)
        body_len = total - 12
        body = f.read(body_len)
        trailer = f.read(4)
        if len(body) < body_len or len(trailer) < 4:
            return
        if btype == 0x00000006 and len(body) >= 20:  # Enhanced Packet Block
            caplen = struct.unpack_from("<I", body, 12)[0]
            pkt = body[20:20 + caplen]
            yield from _link_packets(1, pkt)  # live1 is Ethernet
        elif btype == 0x00000003 and len(body) >= 4:  # Simple Packet Block
            orig = struct.unpack_from("<I", body, 0)[0]
            yield from _link_packets(1, body[4:4 + orig])


def _link_packets(link: int, data: bytes):
    if link == 1:  # Ethernet
        yield from _ipv4_packets_from_ethernet(data)
    elif link == 0 and len(data) >= 4:  # loopback / BSD null
        yield from _ipv4_packets_from_ethernet(b"\x00" * 12 + b"\x08\x00" + data[4:])
    elif link == 12:  # raw IP
        fake = b"\x00" * 12 + b"\x08\x00" + data
        yield from _ipv4_packets_from_ethernet(fake)

# Osmosis MEDIA_PROTOCOL.md camera-control surface (Nano-grounded; Pocket may differ).
HUNT = {
    (0x02, 0x01): "ShootPhoto",
    (0x02, 0x02): "RecordStartStop",
    (0x02, 0x0C): "EnterPlayback",
    (0x02, 0x61): "CameraStatusPoll",
    (0x02, 0x68): "Unknown0268_liveEntry",
    (0x02, 0x80): "CameraStatusPush",
    (0x02, 0x8E): "ParamGetSet",
    (0x02, 0xA0): "CameraStateQuery",
    (0x02, 0xDC): "StoragePush",
    (0x02, 0xE1): "SetShootingMode",
    (0x09, 0xA8): "LiveViewEnableIDR",
    (0x00, 0x6A): "SetTime",
    (0x00, 0x99): "Subscribe",
    (0x00, 0x88): "AppPresence",
    (0x00, 0x81): "DeviceInfo",
    (0x03, 0xDA): "GimbalInit",
    (0x04, 0x05): "GimbalTelemetry",
    (0x0D, 0x02): "Battery",
    (0x53, 0x10): "SessionWake",
}

ASCII_RE = re.compile(rb"[ -~]{4,}")


def pkt_type(data: bytes) -> int | None:
    if len(data) < 7:
        return None
    return data[6]


def extract_ascii(payload: bytes) -> list[str]:
    return [m.group().decode("ascii") for m in ASCII_RE.finditer(payload)]


def subscribe_name(payload: bytes) -> str | None:
    """0x00/0x99 request: name after innerLen/nameLen (Osmosis layout)."""
    if len(payload) < 17:
        return None
    # 02 02 00 00 | subId u32 | 00 00 00 | innerLen u16 | nameLen u16 | name
    name_len = payload[15] | (payload[16] << 8)
    if 17 + name_len > len(payload) or name_len > 80:
        return None
    name = payload[17 : 17 + name_len]
    try:
        s = name.decode("ascii")
    except UnicodeDecodeError:
        return None
    if s.isprintable() and s.replace("_", "").isalnum():
        return s
    return None


def push_name(payload: bytes) -> str | None:
    """0x00/0x99 push: 02 06 00 00 | idx | 00 00 00 | total_len | name_len | name."""
    if len(payload) < 17 or payload[:2] != b"\x02\x06":
        return None
    name_len = payload[15] | (payload[16] << 8)
    if 17 + name_len > len(payload) or name_len > 80:
        return None
    try:
        s = payload[17 : 17 + name_len].decode("ascii")
    except UnicodeDecodeError:
        return None
    if s.isprintable() and s.replace("_", "").isalnum():
        return s
    return None


def main(path: str) -> None:
    packets = read_packets(path)
    print(f"# {path}: {len(packets)} IP packets\n")

    pkt_types = collections.Counter()
    udp_9004 = 0
    tcp_7001 = 0
    cmd_stats = collections.defaultdict(lambda: {
        "n": 0,
        "to_cam": 0,
        "from_cam": 0,
        "flags": collections.Counter(),
        "payloads": collections.Counter(),
        "receivers": collections.Counter(),
        "senders": collections.Counter(),
        "ascii": collections.Counter(),
        "first_pl": None,
    })
    hunt_hits = []
    sub_req = collections.Counter()
    sub_push = collections.Counter()
    enable_events = []
    unknown_to_cam = []

    pkt_i = 0
    for proto, src, sport, dst, dport, data in packets:
        pkt_i += 1
        if proto == "UDP" and 9004 in (sport, dport):
            udp_9004 += 1
            pt = pkt_type(data)
            if pt is not None:
                pkt_types[pt] += 1
        if proto == "TCP" and 7001 in (sport, dport):
            tcp_7001 += 1

        to_cam = dst == CAMERA_IP
        for fr in scan_frames(data):
            key = (fr["set"], fr["cmd"])
            st = cmd_stats[key]
            st["n"] += 1
            if to_cam:
                st["to_cam"] += 1
            else:
                st["from_cam"] += 1
            st["flags"][fr["flags"]] += 1
            st["receivers"][fr["receiver"]] += 1
            st["senders"][fr["sender"]] += 1
            pl = bytes(fr["payload"])
            st["payloads"][pl] += 1
            if st["first_pl"] is None:
                st["first_pl"] = pl
            for a in extract_ascii(pl):
                st["ascii"][a] += 1

            if key == (0x00, 0x99):
                if to_cam and fr["flags"] == 0x40:
                    n = subscribe_name(pl)
                    if n:
                        sub_req[n] += 1
                n = push_name(pl)
                if n:
                    sub_push[n] += 1
                # also scrape any cam*/timecode/shutter/iso strings
                for a in extract_ascii(pl):
                    if a.startswith(("cam", "shutter", "timecode", "audio_", "v_quality", "gui_")):
                        if to_cam:
                            sub_req[a] += 0  # ensure key exists if parser missed
                            if a not in sub_req:
                                sub_req[a] += 1

            if key in HUNT or key not in KNOWN_COMMANDS:
                if to_cam and fr["flags"] in (0x40, 0x80) and key not in {
                    (0x00, 0x88), (0x00, 0x81), (0x00, 0x99), (0x00, 0x2B),
                    (0x09, 0xA8), (0x03, 0xDA), (0x53, 0x10), (0x07, 0x45),
                    (0x07, 0x07), (0x07, 0x0E),
                }:
                    unknown_to_cam.append((pkt_i, key, fr, pl, proto, sport, dport))

            if key == (0x09, 0xA8):
                enable_events.append((pkt_i, to_cam, fr["flags"], pl.hex(), fr["seq"]))

            if key in ((0x02, 0x01), (0x02, 0x02), (0x02, 0xE1), (0x02, 0x8E), (0x02, 0x68),
                       (0x00, 0x6A), (0x02, 0xA0), (0x02, 0x61)):
                hunt_hits.append((pkt_i, to_cam, key, fr["flags"], pl.hex()[:80], fr["seq"],
                                  fr["sender"], fr["receiver"]))

    print("## Transport")
    print(f"  UDP 9004 packets: {udp_9004}")
    print(f"  TCP 7001 packets with payload: {tcp_7001}")
    print("  pktType histogram (UDP 9004, byte 6 of datagram):")
    for pt, n in sorted(pkt_types.items()):
        print(f"    0x{pt:02x}  {n:>7}")

    print("\n## DUML opcodes (all CRC-valid frames)")
    print(f"{'cmd':<12} {'name':<24} {'n':>6} {'->cam':>6} {'<-cam':>6}  notes")
    for (s, c), st in sorted(cmd_stats.items(), key=lambda kv: (-kv[1]["n"], kv[0])):
        name = HUNT.get((s, c)) or cmd_name(s, c)
        uniq = len(st["payloads"])
        flags = ",".join(f"0x{f:02x}×{n}" for f, n in st["flags"].most_common())
        rec = ",".join(f"0x{r:02x}×{n}" for r, n in st["receivers"].most_common(3))
        note = f"uniq_pl={uniq} flags={flags} rx={rec}"
        print(f"  {cmd(s,c):<10} {name:<24} {st['n']:>6} {st['to_cam']:>6} {st['from_cam']:>6}  {note}")

    print("\n## Unique payloads for interesting / unknown opcodes")
    interesting = set(HUNT) | {k for k in cmd_stats if k not in KNOWN_COMMANDS}
    for key in sorted(interesting):
        st = cmd_stats.get(key)
        if not st:
            continue
        print(f"\n### {cmd(*key)} {HUNT.get(key) or cmd_name(*key)}  n={st['n']}")
        for pl, n in st["payloads"].most_common(12):
            hx = pl.hex()
            if len(hx) > 96:
                hx = hx[:96] + f"...(+{len(pl)-48}B)"
            ascii_bits = extract_ascii(pl)
            extra = f"  ascii={ascii_bits[:4]}" if ascii_bits else ""
            print(f"    ×{n:<5} {len(pl):>4}B  {hx}{extra}")

    print("\n## 0x09/0xa8 LiveViewEnable events")
    if not enable_events:
        print("  (none)")
    for ev in enable_events:
        pkt_i, to_cam, flags, hx, seq = ev
        print(f"  pkt#{pkt_i} {'->cam' if to_cam else '<-cam'} flags=0x{flags:02x} seq=0x{seq:04x} payload={hx}")

    print("\n## Hunt hits (record/photo/mode/param/time/0x68)")
    if not hunt_hits:
        print("  (none)")
    for h in hunt_hits[:80]:
        pkt_i, to_cam, key, flags, hx, seq, snd, rcv = h
        print(f"  pkt#{pkt_i} {'->cam' if to_cam else '<-cam'} {cmd(*key)} "
              f"snd=0x{snd:02x} rcv=0x{rcv:02x} flags=0x{flags:02x} seq=0x{seq:04x} {hx}")
    if len(hunt_hits) > 80:
        print(f"  ... +{len(hunt_hits)-80} more")

    print("\n## Subscribe requests (0x00/0x99 ->cam, parsed names)")
    if not sub_req:
        print("  (none parsed)")
    for name, n in sorted(sub_req.items()):
        print(f"  ×{n}  {name}")

    print("\n## Subscribe / param pushes (0x00/0x99, parsed names from 02 06 layout)")
    if not sub_push:
        print("  (none parsed)")
    for name, n in sorted(sub_push.items(), key=lambda kv: (-kv[1], kv[0])):
        print(f"  ×{n}  {name}")

    print("\n## App->camera requests that are not the known live-view spine")
    if not unknown_to_cam:
        print("  (none)")
    shown = 0
    for pkt_i, key, fr, pl, proto, sport, dport in unknown_to_cam:
        if shown >= 40:
            break
        hx = pl.hex()
        if len(hx) > 80:
            hx = hx[:80] + "..."
        print(f"  pkt#{pkt_i} {proto}:{sport}->{dport} {cmd(*key)} "
              f"{HUNT.get(key) or cmd_name(*key)} flags=0x{fr['flags']:02x} "
              f"snd=0x{fr['sender']:02x} rcv=0x{fr['receiver']:02x} {hx}")
        shown += 1
    if len(unknown_to_cam) > 40:
        print(f"  ... +{len(unknown_to_cam)-40} more")

    # ASCII scrape across all frames for setting names
    print("\n## ASCII tokens containing setting-ish names (all frames)")
    tokens = collections.Counter()
    for (s, c), st in cmd_stats.items():
        for a, n in st["ascii"].items():
            if any(k in a.lower() for k in (
                "iso", "shutter", "wb", "focus", "ae", "af", "timecode", "record",
                "color", "fov", "zebra", "peak", "lut", "iris", "expo", "mode",
                "camcap", "cam_", "video", "photo", "fps",
            )):
                tokens[a] += n
    for a, n in tokens.most_common(80):
        print(f"  ×{n}  {a}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
