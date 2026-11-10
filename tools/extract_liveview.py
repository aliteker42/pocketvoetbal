#!/usr/bin/env python3
"""Turn a capture of DJI Mimo <-> Osmo traffic into a playable HEVC or AVC file.

Pocket live view is HEVC/H.265; Nano is AVC/H.264 High. Both are 1280x720 ~25 fps
on DUML datalink **pktType 0x02** UDP 9004, in the clear. Each packet is:

    [8B transport header][12B fragment header][HEVC bytes]

Fragment header (bytes 8..19 of the UDP payload):
    [8:12]  routing [ack=seq-8][seq]      (same as any datalink packet)
    [12:16] 00 00 00 00
    [16]    frame number (mod 256)        <- constant across one frame's packets
    [17]    0x0e / 0x8e = even/odd half of the fragment pair
    [18:20] fragment index within the frame (u16 LE)
    [20:]   video bytes

Fragments arrive in order, so capture order is correct. Every frame begins with a DJI
private marker `00 00 01 ff` + metadata (~13 bytes, NAL type 63) before the standard
Annex-B NALs; parameter sets (VPS/SPS/PPS) only appear on keyframes (~every 20 s).

This groups fragments into frames by byte 16, starts at the first keyframe, strips the
DJI marker per frame, and writes a clean HEVC elementary stream.

Usage:
    extract_liveview.py capture.pcap live.h265
    ffmpeg -f hevc -i live.h265 -c copy live.mp4      # then play live.mp4
    extract_liveview.py --selftest

Needs scapy (`pip install scapy`). Apple's pcapng (what macOS tcpdump writes for an
rvi0 capture) is auto-normalised via tcpdump, which scapy can then read.
"""
import os
import subprocess
import sys
import tempfile

CAMERA_IP = "192.168.2.1"


def read_video_packets(path):
    """Return the UDP payloads of the pktType-0x02 (video) packets, camera -> phone."""
    # scapy can't parse Apple pcapng; round-trip through tcpdump to normalise it first.
    fd, tmp = tempfile.mkstemp(suffix=".pcap")
    os.close(fd)
    try:
        # Don't check the exit code: a capture stopped with Ctrl-C ends on a truncated
        # block, so tcpdump exits non-zero after writing an otherwise-complete file.
        subprocess.run(["tcpdump", "-r", path, "-w", tmp],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if os.path.getsize(tmp) == 0:
            sys.exit(f"tcpdump produced no readable packets from {path}")
        try:
            from scapy.all import rdpcap, IP, UDP
        except ImportError:
            sys.exit("scapy is required: pip install scapy")
        out = []
        for pkt in rdpcap(tmp):
            if IP in pkt and UDP in pkt and pkt[IP].src == CAMERA_IP:
                pl = bytes(pkt[UDP].payload)
                if len(pl) >= 20 and pl[6] == 0x02:
                    out.append(pl)
        return out
    finally:
        os.unlink(tmp)


def first_real_nal(frame):
    """Offset of the first standard NAL, skipping the DJI `00 00 01 ff` marker.

    Pocket is HEVC (VPS/SPS `0x40`/`0x42`); Nano is AVC (SPS/IDR `0x67`/`0x65`).
    HEVC-type math classifies those AVC bytes as >40, so only skip `0xff`.
    """
    i = 0
    while True:
        j = frame.find(b"\x00\x00\x01", i)
        if j < 0:
            return None
        if frame[j + 3] != 0xff:
            return j
        i = j + 3


def extract(packets):
    """(clean HEVC bytes, frame count, keyframe count)."""
    frames, cur_no, cur = [], None, bytearray()
    for pl in packets:
        if cur and pl[16] != cur_no:            # byte 16 changed -> new frame
            frames.append(bytes(cur))
            cur = bytearray()
        cur_no, cur = pl[16], cur + pl[20:]
    if cur:
        frames.append(bytes(cur))

    # Pocket HEVC SPS is 0x42; Nano AVC SPS is 0x67. Same DJI marker / fragment layout.
    keyframes = [i for i, f in enumerate(frames)
                 if b"\x00\x00\x01\x42" in f or b"\x00\x00\x01\x67" in f]
    start = keyframes[0] if keyframes else 0
    out = bytearray()
    for f in frames[start:]:
        r = first_real_nal(f)
        out += f[r:] if r is not None else f
    return bytes(out), len(frames), len(keyframes)


def selftest():
    marker = b"\x00\x00\x01\xff" + bytes(13)                 # DJI per-frame marker (NAL type 63)
    assert first_real_nal(marker + b"\x00\x00\x01\x40\x01") == len(marker)  # finds VPS after it
    assert first_real_nal(b"\x00\x00\x01\x26\xaf") == 0      # an IDR slice at the very start
    assert first_real_nal(b"no start codes") is None
    print("selftest OK")


def main(argv):
    if len(argv) == 2 and argv[1] == "--selftest":
        selftest()
    elif len(argv) == 3:
        pkts = read_video_packets(argv[1])
        data, nframes, nkey = extract(pkts)
        with open(argv[2], "wb") as f:
            f.write(data)
        print(f"{len(pkts)} video packets -> {nframes} frames ({nkey} keyframes) "
              f"-> {argv[2]} ({len(data)} bytes)")
        head = data[:64]
        demux = "h264" if b"\x00\x00\x01\x67" in head or b"\x00\x00\x00\x01\x67" in head else "hevc"
        print(f"play:  ffmpeg -f {demux} -i {argv[2]} -c copy {argv[2].rsplit('.', 1)[0]}.mp4")
    else:
        print(__doc__)
        sys.exit(1)


if __name__ == "__main__":
    main(sys.argv)
