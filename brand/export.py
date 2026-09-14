#!/usr/bin/env python3
"""
Renders the PNG and animated WebP files that Modrinth, Spigot and BuiltByBit accept.

    python brand/export.py           # everything
    python brand/export.py icon      # just the icon

Needs rsvg-convert (apt install librsvg2-bin) and ffmpeg. The banner also needs
Space Grotesk and Inter installed, or its text renders in a fallback font.
"""

import os
import shutil
import subprocess
import sys
import tempfile
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build  # noqa: E402

# Rendered at 512 and scaled down, so it stays crisp on high-DPI screens.
# Above these qualities the file grows and the flat art stops improving.
ICON_WEBP = [(192, "bad-icon-192-animated.webp", 52),
             (384, "bad-icon-384-animated.webp", 48)]
BANNER_WEBP = ("bad-banner-animated.webp", 62)


def rasterise(svg, png):
    subprocess.run(["rsvg-convert", "-o", png], input=svg.encode(), check=True)


def render_frames(frames, tmp):
    paths = []
    with ThreadPoolExecutor() as pool:
        for i, svg in enumerate(frames):
            path = os.path.join(tmp, f"f{i:04d}.png")
            paths.append(path)
            pool.submit(rasterise, svg, path)
    return len(paths)


def encode(tmp, fps, name, quality, size=None):
    out = os.path.join(HERE, name)
    scale = ["-vf", f"scale={size}:{size}:flags=lanczos"] if size else []
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error",
         "-framerate", str(fps), "-i", os.path.join(tmp, "f%04d.png"), *scale,
         "-c:v", "libwebp_anim", "-loop", "0",
         "-q:v", str(quality), "-compression_level", "6", out],
        check=True)
    print(f"{name}  {os.path.getsize(out) / 1024:.0f} KB")


def fonts_missing():
    # Inter's variable release names itself "Inter Variable", which the SVG font stack also accepts.
    missing = []
    for names in (("Space Grotesk",), ("Inter", "Inter Variable")):
        found = [subprocess.run(["fc-match", "-f", "%{family}", n],
                                capture_output=True, text=True).stdout for n in names]
        if not any(n in f.split(",") for n, f in zip(names, found)):
            missing.append(names[0])
    return missing


def export_icon():
    rasterise(build.icon(), os.path.join(HERE, "bad-icon-512.png"))
    print("bad-icon-512.png")
    tmp = tempfile.mkdtemp(prefix="bad-icon-")
    try:
        n = render_frames(build.icon_frames(), tmp)
        print(f"icon: {n} frames at {build.ICON_FPS} fps")
        for size, name, q in ICON_WEBP:
            encode(tmp, build.ICON_FPS, name, q, size)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def export_banner():
    missing = fonts_missing()
    if missing:
        sys.exit(f"install {' and '.join(missing)} first, or the banner text renders in a fallback font")
    for w, h, suffix in ((1280, 420, "1280x420"), (1200, 600, "1200x600")):
        name = f"bad-banner-{suffix}.png"
        rasterise(build.banner(w, h), os.path.join(HERE, name))
        print(name)
    tmp = tempfile.mkdtemp(prefix="bad-banner-")
    try:
        n = render_frames(build.banner_frames(), tmp)
        print(f"banner: {n} frames at {build.BANNER_FPS} fps")
        encode(tmp, build.BANNER_FPS, *BANNER_WEBP)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    for tool in ("rsvg-convert", "ffmpeg", "fc-match"):
        if not shutil.which(tool):
            sys.exit(f"{tool} is not on PATH")
    what = sys.argv[1:] or ["icon", "banner"]
    if "icon" in what:
        export_icon()
    if "banner" in what:
        export_banner()


if __name__ == "__main__":
    main()
