#!/usr/bin/env python3
"""Generates the Android 15 boot animation shipped by this runtime.

The redroid 15 (64only) image has no /system/media at all: it ships
/system/bin/bootanimation and /system/lib64/libbootanimation.so but no
bootanimation.zip, and vendor/build.prop sets debug.sf.nobootanimation=1.
A device without an animation asset has nothing for the real bootanimation
binary to draw, so the runtime supplies one -- exactly what an OEM ships in
/system/media/bootanimation.zip. The binary stays AOSP's; only the artwork is
ours.

Format (read from libbootanimation.so and AOSP's BootAnimation):
  * the zip must be STORED (the library rejects a compressed archive:
    "bootanimation.zip is compressed; must be only stored");
  * desc.txt first line: "<width> <height> <fps>";
  * "p <loop> <pause> <part>" lines; loop=0 means forever;
  * frames are PNGs inside the part directory, played in name order.

Artwork: the Android robot head on black, with a highlight sweeping across it.
Deterministic output (no timestamps), so the asset hash is reproducible.
"""
import os
import struct
import zlib
import zipfile

W, H = 720, 1280
FPS = 24
FRAMES = 36
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "bootanim", "bootanimation.zip")

GREEN = (0x3D, 0xDC, 0x84)
CX, CY = W / 2.0, 660.0
R = 210.0
BODY_BOTTOM = CY + R * 0.80
EYE_R = 24.0
EYE_DX = 78.0
EYE_DY = -16.0
ANT_Y = CY - R - 92.0
ANT_DX = 96.0
BOX = (int(CX - R - 40), int(ANT_Y - 20), int(CX + R + 40), int(BODY_BOTTOM + 2))


def png(width, height, rgb):
    raw = bytearray()
    stride = width * 3
    for y in range(height):
        raw.append(0)
        raw.extend(rgb[y * stride:(y + 1) * stride])

    def chunk(tag, payload):
        return (struct.pack(">I", len(payload)) + tag + payload +
                struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    header = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) +
            chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def head(px, py):
    if py <= CY:
        if (px - CX) ** 2 + (py - CY) ** 2 > R * R:
            return False
    elif abs(px - CX) > R or py > BODY_BOTTOM:
        return False
    if (px - (CX - EYE_DX)) ** 2 + (py - (CY + EYE_DY)) ** 2 < EYE_R ** 2:
        return False
    if (px - (CX + EYE_DX)) ** 2 + (py - (CY + EYE_DY)) ** 2 < EYE_R ** 2:
        return False
    return True


def antenna(px, py):
    for side in (-1, 1):
        x0 = CX + side * ANT_DX
        for t in range(0, 33):
            q = t / 32.0
            x = x0 + side * 24.0 * q
            y = ANT_Y - 24.0 * q
            if (px - x) ** 2 + (py - y) ** 2 <= 6.0 ** 2:
                return True
    return False


def build_mask():
    """Alpha (0..255) of the artwork, computed once over the bounding box."""
    x0, y0, x1, y1 = BOX
    w, h = x1 - x0, y1 - y0
    mask = bytearray(w * h)
    ss = 3
    for y in range(h):
        for x in range(w):
            hits = 0
            for sy in range(ss):
                for sx in range(ss):
                    px = x0 + x + (sx + 0.5) / ss
                    py = y0 + y + (sy + 0.5) / ss
                    if head(px, py) or antenna(px, py):
                        hits += 1
            if hits:
                mask[y * w + x] = (hits * 255) // (ss * ss)
    return mask


def frame(index, mask):
    x0, y0, x1, y1 = BOX
    w, h = x1 - x0, y1 - y0
    pixels = bytearray(W * H * 3)  # black
    travel = w + 320
    shine = -160.0 + (index / float(FRAMES)) * travel
    for y in range(h):
        row = y * w
        base = ((y0 + y) * W + x0) * 3
        for x in range(w):
            a = mask[row + x]
            if not a:
                continue
            i = base + x * 3
            if a == 255:
                alpha = 1.0
            else:
                alpha = a / 255.0
            d = abs(x - shine)
            glow = max(0.0, 1.0 - d / 150.0) * alpha
            r = int(GREEN[0] + (255 - GREEN[0]) * glow * 0.60)
            g = int(GREEN[1] + (255 - GREEN[1]) * glow * 0.60)
            b = int(GREEN[2] + (255 - GREEN[2]) * glow * 0.60)
            pixels[i] = int(pixels[i] * (1 - alpha) + r * alpha)
            pixels[i + 1] = int(pixels[i + 1] * (1 - alpha) + g * alpha)
            pixels[i + 2] = int(pixels[i + 2] * (1 - alpha) + b * alpha)
    return png(W, H, pixels)


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    mask = build_mask()
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", "%d %d %d\np 0 0 part0\n" % (W, H, FPS))
        for i in range(FRAMES):
            z.writestr("part0/%04d.png" % i, frame(i, mask))
    print("bootanimation.zip: %d frames %dx%d @%dfps, %d bytes"
          % (FRAMES, W, H, FPS, os.path.getsize(OUT)))


if __name__ == "__main__":
    main()
