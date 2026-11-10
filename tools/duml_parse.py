#!/usr/bin/env python3
"""Turn a PCAP of DJI Mimo <-> camera traffic into DUML frames + flow stats.

The point of this tool is to find the *live-view* command. Capture Mimo idle,
then capture Mimo showing live view, then:

    duml_parse.py --diff idle.pcap live.pcap

...prints the DUML commands and UDP flows that appear only in the live-view
capture. The new command is almost certainly the "start live view" enable; the
new fat UDP flow is almost certainly the H.264/H.265 stream.

Usage:
    duml_parse.py CAPTURE.pcap                 # list DUML frames + UDP/TCP flow summary
    duml_parse.py --diff IDLE.pcap LIVE.pcap   # what's new in LIVE vs IDLE
    duml_parse.py --selftest                   # verify the frame scanner (no pcap needed)

Reading pcaps needs scapy:  pip install scapy   (or: python3 -m pip install scapy)
The scanner validates CRC8+CRC16, so it finds real DUML frames inside the UDP
transport/routing wrapper without having to model the wrapper.
"""
import sys
from collections import defaultdict

CAMERA_IP = "192.168.2.1"

# Commands we already understand (from Osmosis). The live-view enable command will NOT be in here —
# so in a --diff, the candidate labelled UNKNOWN is the interesting one.
KNOWN_COMMANDS = {
    (0x07, 0x45): "SetPairingPIN", (0x07, 0x46): "PairApproval", (0x07, 0x07): "GetWifiSsid",
    (0x07, 0x0E): "GetWifiPassword", (0x07, 0x0C): "GetWifiMac", (0x00, 0x2B): "SessionPing",
    (0x53, 0x10): "SessionWake", (0x00, 0x81): "DeviceInfo", (0x00, 0x88): "AppPresence",
    (0x00, 0x99): "Subscribe", (0x00, 0x6A): "SetTime", (0x03, 0xDA): "GimbalInit",
    (0x02, 0x0C): "EnterPlayback", (0x00, 0x26): "MediaListReq", (0x00, 0x27): "MediaListResp",
    (0x02, 0x80): "StorageActive", (0x02, 0xDC): "StoragePerStore", (0x0D, 0x02): "Battery",
    (0x04, 0x05): "GimbalTelemetry", (0x00, 0x00): "GetVersion",
    (0x09, 0xA8): "LiveViewEnable",   # starts the pktType-0x02 HEVC stream (RE'd 2026-08-13)
}


def cmd_name(s, c):
    return KNOWN_COMMANDS.get((s, c), "** UNKNOWN **")


def crc8(data, c=0x77):
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0x8C if c & 1 else c >> 1
    return c & 0xFF


def crc16(data, c=0x3692):
    for b in data:
        c ^= b
        for _ in range(8):
            c = (c >> 1) ^ 0x8408 if c & 1 else c >> 1
    return c & 0xFFFF


def scan_frames(buf):
    """Yield every CRC-valid DUML frame in buf as a dict."""
    i, n = 0, len(buf)
    while i < n:
        if buf[i] != 0x55:
            i += 1
            continue
        if i + 13 > n:
            break
        total = buf[i + 1] | ((buf[i + 2] & 0x03) << 8)
        if buf[i + 2] >> 2 != 1 or total < 13 or i + total > n:
            i += 1
            continue
        f = buf[i:i + total]
        if crc8(f[0:3]) != f[3] or crc16(f[0:total - 2]) != (f[total - 2] | (f[total - 1] << 8)):
            i += 1
            continue
        yield {
            "sender": f[4], "receiver": f[5],
            "seq": f[6] | (f[7] << 8), "flags": f[8],
            "set": f[9], "cmd": f[10],
            "payload": bytes(f[11:total - 2]),
        }
        i += total


def read_packets(path):
    """Return list of (proto, src, sport, dst, dport, payload_bytes)."""
    try:
        from scapy.all import rdpcap, IP, UDP, TCP
    except ImportError:
        sys.exit("scapy is required to read pcaps:  pip install scapy")
    out = []
    for pkt in rdpcap(path):
        if IP not in pkt:
            continue
        ip = pkt[IP]
        if UDP in pkt:
            u = pkt[UDP]
            out.append(("UDP", ip.src, u.sport, ip.dst, u.dport, bytes(u.payload)))
        elif TCP in pkt:
            t = pkt[TCP]
            data = bytes(t.payload)
            if data:
                out.append(("TCP", ip.src, t.sport, ip.dst, t.dport, data))
    return out


def analyze(packets):
    """Return (commands set of (set,cmd), flows dict key->[pkts,bytes], frames list)."""
    commands, frames = set(), []
    flows = defaultdict(lambda: [0, 0])
    for proto, src, sport, dst, dport, data in packets:
        key = (proto, src, sport, dst, dport)
        flows[key][0] += 1
        flows[key][1] += len(data)
        for fr in scan_frames(data):
            to_cam = dst == CAMERA_IP
            frames.append((("->cam" if to_cam else "<-cam"), fr))
            commands.add((fr["set"], fr["cmd"]))
    return commands, flows, frames


def cmd(s, c):
    return f"0x{s:02x}/0x{c:02x}"


def flow_str(key):
    proto, src, sp, dst, dp = key
    return f"{proto} {src}:{sp} -> {dst}:{dp}"


def list_capture(path):
    commands, flows, frames = analyze(read_packets(path))
    print(f"# {path}: {len(frames)} DUML frames, {len(commands)} distinct commands\n")
    print("## DUML frames")
    for direction, fr in frames:
        pl = fr["payload"].hex()
        if len(pl) > 48:
            pl = pl[:48] + f"...(+{len(fr['payload']) - 24}B)"
        name = cmd_name(fr['set'], fr['cmd'])
        print(f"  {direction}  {cmd(fr['set'], fr['cmd'])} {name:<16} seq={fr['seq']:#06x} flags={fr['flags']:#04x}  {pl}")
    print("\n## UDP/TCP flows (by bytes — a fat one is likely the video stream)")
    for key, (n, nbytes) in sorted(flows.items(), key=lambda kv: -kv[1][1]):
        print(f"  {nbytes:>10}B  {n:>6} pkts   {flow_str(key)}")


def diff(idle_path, live_path):
    idle_cmds, idle_flows, _ = analyze(read_packets(idle_path))
    live_cmds, live_flows, _ = analyze(read_packets(live_path))
    print(f"# Present in {live_path} but NOT {idle_path}\n")
    print("## Candidate enable commands (DUML set/cmd only seen with live view)")
    print("   (the live-view enable is almost certainly an ** UNKNOWN ** one)")
    new_cmds = sorted(live_cmds - idle_cmds)
    if not new_cmds:
        print("  (none)")
    for s, c in new_cmds:
        print(f"  {cmd(s, c)}  {cmd_name(s, c)}")
    print("\n## Candidate stream flows (UDP/TCP endpoints only seen with live view)")
    idle_keys = {k[:5] for k in idle_flows}
    new_flows = [(k, v) for k, v in live_flows.items() if k not in idle_keys]
    if not new_flows:
        print("  (none)")
    for key, (n, nbytes) in sorted(new_flows, key=lambda kv: -kv[1][1]):
        print(f"  {nbytes:>10}B  {n:>6} pkts   {flow_str(key)}")


def selftest():
    enter = bytes([0x55, 0x11, 0x04, 0x92, 0x02, 0x01, 0x00, 0xA0,
                   0x40, 0x02, 0x0C, 0x01, 0x01, 0x00, 0x01, 0xB6, 0x3B])
    got = list(scan_frames(enter))
    assert len(got) == 1, got
    assert got[0]["set"] == 0x02 and got[0]["cmd"] == 0x0C, got[0]
    assert got[0]["payload"] == bytes([0x01, 0x01, 0x00, 0x01]), got[0]
    # Found even when wrapped in a transport header + trailing junk.
    assert len(list(scan_frames(bytes(8) + enter + bytes([0xFF, 0xFF])))) == 1
    assert crc8([0x55, 0x33, 0x04]) == 0xC2
    print("selftest OK")


def main(argv):
    if len(argv) == 2 and argv[1] == "--selftest":
        selftest()
    elif len(argv) == 4 and argv[1] == "--diff":
        diff(argv[2], argv[3])
    elif len(argv) == 2 and not argv[1].startswith("-"):
        list_capture(argv[1])
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv)
