#!/usr/bin/env python3
"""Measure DJI D-Log / D-Log2 saturation ceilings per exposure index (EI).

DJI's 2017 D-Log white paper: the log curve is defined at EI 1600 and SCALED
for other exposure indices — "when EI value is below 1600, the output code
value will not reach saturation level (e.g. 1023 for 10bit)". This script
measures that per-EI ceiling from real Pocket 4/4P recordings so the scope
anchors in Sources/OpenPocketViewCore/LiveColorScience.swift can pin the 100
line (and zebra/clip lights) at the true saturation code instead of the curve
top.

Per clip it reports: gamma, EI, observed 10-bit luma min/max, the implied
curve-fraction ceiling ((Ymax-64)/876, legal-range wire) and its tap byte
(round(frac*255)), the decoded linear ceiling, stops above 18% grey, and the
ceiling-linear normalized to EI 1600 (assuming linear EI scaling — consistent
clips agree on this column).

NOTE — recordings are SECONDARY evidence. The scopes meter the LIVE 720p HEVC
feed, whose measured ceiling sits LOWER than the recording ceiling at the same
EI (live tap byte 247 for D-Log2 at EI 1600, 223 for D-Log — vs recording
byte ~211–228). 188 is recoverable highlight, not the live ceiling.
The shipped `ScopeExposureCeiling` table in LiveColorScience.swift is
calibrated from the live tap ("scope tap" / "scope max" lines in the pullable
control log — tools/pull-control-log.sh); use this script to cross-check
EI-to-EI scaling and file behavior only.

Recording workflow (if you still want file-side numbers): record a clip per
(gamma, EI) with the sensor blasted (point at a lamp, slow shutter) so the
ceiling is actually reached, plus a lens-capped clip for the floor. Drop them
in ~/Downloads and re-run:

    python3 tools/measure_dlog_ceilings.py            # all Video_*DJI* in ~/Downloads
    python3 tools/measure_dlog_ceilings.py clip1.mp4  # explicit paths

Only keyframes are decoded (-skip_frame nokey) so full clips scan in seconds.
"""

import glob
import json
import math
import os
import subprocess
import sys

LEGAL_BLACK = 64.0   # container10 = 64 + curve * 876 (measured Pocket wire)
LEGAL_SPAN = 876.0


def dlog_decode(e):
    if e <= 0.14:
        return (e - 0.0929) / 6.025
    return (10 ** (3.89616 * e - 2.27752) - 0.0108) / 0.9892


DL2_A = 16.285770761945304
DL2_K1 = 0.059439938321493
DL2_B1 = 0.304985337243402
DL2_K2 = 2.960935245492250
DL2_B2 = 0.148314799066323
DL2_PEAK = 475.0


def dlog2_decode(e):
    if e >= DL2_B1:
        return (DL2_PEAK / (2 ** DL2_A - 1)) * (2 ** (DL2_A * e) - 1)
    if e >= DL2_B2:
        return 2 ** ((e - DL2_B1) / DL2_K1 + math.log2(0.18))
    return (e - DL2_B2) / DL2_K2 + 0.028961695254132


DECODERS = {"D-Log": dlog_decode, "D-Log2": dlog2_decode}


def probe(path):
    out = subprocess.run(
        ["ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", path],
        capture_output=True, text=True, check=True).stdout
    tags = json.loads(out).get("format", {}).get("tags", {})
    gamma = tags.get("com.dji.camera.ColorGammaSxS", "?")
    ei = tags.get("com.dji.camera.ExposureIndexAsa")
    return gamma, int(ei) if ei and ei.isdigit() else None


def luma_extremes(path):
    """(ymin, ymax) over all keyframes, in the container's native bit depth."""
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-skip_frame", "nokey", "-i", path,
         "-map", "0:v:0", "-an", "-sn", "-dn",
         "-vf", "signalstats,metadata=mode=print:file=-",
         "-f", "null", "-"],
        capture_output=True, text=True, check=True).stdout
    ymin, ymax = math.inf, -math.inf
    for line in out.splitlines():
        if "signalstats.YMIN=" in line:
            ymin = min(ymin, float(line.rsplit("=", 1)[1]))
        elif "signalstats.YMAX=" in line:
            ymax = max(ymax, float(line.rsplit("=", 1)[1]))
    if not math.isfinite(ymin) or not math.isfinite(ymax):
        raise RuntimeError("no signalstats output for " + path)
    return ymin, ymax


def main(argv):
    paths = argv or sorted(glob.glob(os.path.expanduser("~/Downloads/Video_*DJI*")))
    if not paths:
        print("no clips given and no ~/Downloads/Video_*DJI* found", file=sys.stderr)
        return 1

    rows = []
    for path in paths:
        try:
            gamma, ei = probe(path)
            ymin, ymax = luma_extremes(path)
        except (subprocess.CalledProcessError, RuntimeError) as err:
            print(f"SKIP {os.path.basename(path)}: {err}", file=sys.stderr)
            continue
        frac = (ymax - LEGAL_BLACK) / LEGAL_SPAN
        decode = DECODERS.get(gamma)
        linear = decode(frac) if decode else None
        stops = math.log2(linear / 0.18) if linear and linear > 0 else None
        at1600 = linear * 1600 / ei if linear is not None and ei else None
        rows.append((os.path.basename(path)[:44], gamma, ei, ymin, ymax,
                     frac, linear, stops, at1600))

    print(f"{'clip':44}  {'gamma':7} {'EI':>5} {'Y10min':>6} {'Y10max':>6} "
          f"{'frac':>7} {'byte':>5} {'linear':>8} {'stops':>6} {'lin@1600':>8}")
    for name, gamma, ei, ymin, ymax, frac, linear, stops, at1600 in rows:
        head = (f"{name:44}  {gamma:7} {ei or '?':>5} {ymin:6.0f} {ymax:6.0f} "
                f"{frac:7.4f} {round(frac * 255):5d}")
        if linear is not None:
            tail = (f" {linear:8.2f}"
                    + (f" {stops:6.2f}" if stops is not None else f" {'-':>6}")
                    + (f" {at1600:8.2f}" if at1600 is not None else f" {'-':>8}"))
        else:
            tail = f" {'-':>8} {'-':>6} {'-':>8}"
        print(head + tail)

    # Group: highest observed ceiling per (gamma, EI) — later clips refine, and
    # a blasted-sensor clip dominates an under-lit one automatically.
    groups = {}
    for r in rows:
        if r[6] is None or r[2] is None:
            continue
        key = (r[1], r[2])
        if key not in groups or r[6] > groups[key][0]:
            groups[key] = (r[6], r[5], r[7])
    if groups:
        print("\n// Ready-to-paste Swift (ceilingLinearAtRef rows, refEI = 1600):")
        print("// gamma      EI   ceilingFrac  ceilingLinear  stops>grey  linear@EI1600")
        for (gamma, ei), (linear, frac, stops) in sorted(groups.items()):
            print(f"//  {gamma:8} {ei:5}  {frac:.4f}       {linear:8.2f}      "
                  f"{stops:5.2f}       {linear * 1600 / ei:8.2f}")
        for gamma in sorted({g for g, _ in groups}):
            best = max((ei for g, ei in groups if g == gamma))  # trust highest EI
            linear = groups[(gamma, best)][0] * 1600 / best
            name = "dlog2" if gamma == "D-Log2" else "dlog"
            print(f"case .{name}: {linear:.1f}  // measured EI {best} ceiling, "
                  f"scaled to EI 1600")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
