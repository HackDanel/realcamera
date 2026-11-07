#!/usr/bin/env python3
"""
Generates RealCamera textures, item/block models, blockstates and sounds.

Run from the project root:  python3 tools/gen_assets.py
Everything it writes is overwritten on every run, so tweak the code here, not the output files.
Requires Pillow and numpy; ffmpeg (libvorbis) for sounds.
"""
import json
import math
import os
import subprocess
import tempfile
import wave

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
ASSETS = os.path.join(ROOT, "src/main/resources/assets/realcamera")
NS = "realcamera"
RNG = np.random.default_rng(1105)


# ----------------------------------------------------------------------------- io helpers

def path(*parts):
    p = os.path.join(ASSETS, *parts)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    return p


def save_json(obj, *parts):
    with open(path(*parts), "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def save_png(img, *parts):
    img.save(path(*parts))


def rgba(hex_color, a=255):
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (c[3] if len(c) > 3 else 255,)


def noise_fill(img, box, base, amount=10, seed=None):
    """Fill a rectangle with a colour plus per-pixel brightness noise (matte plastic / metal look)."""
    rng = np.random.default_rng(seed) if seed is not None else RNG
    x0, y0, x1, y1 = box
    px = img.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            n = int(rng.integers(-amount, amount + 1))
            px[x, y] = tuple(max(0, min(255, base[i] + n)) for i in range(3)) + (255,)


# ----------------------------------------------------------------------------- tiny pixel font (3x5)

FONT = {
    "A": ["010", "101", "111", "101", "101"], "B": ["110", "101", "110", "101", "110"],
    "C": ["011", "100", "100", "100", "011"], "D": ["110", "101", "101", "101", "110"],
    "E": ["111", "100", "110", "100", "111"], "F": ["111", "100", "110", "100", "100"],
    "G": ["011", "100", "101", "101", "011"], "I": ["111", "010", "010", "010", "111"],
    "K": ["101", "101", "110", "101", "101"], "L": ["100", "100", "100", "100", "111"],
    "M": ["101", "111", "111", "101", "101"], "N": ["101", "111", "111", "111", "101"],
    "O": ["010", "101", "101", "101", "010"], "P": ["110", "101", "110", "100", "100"],
    "R": ["110", "101", "110", "101", "101"], "S": ["011", "100", "010", "001", "110"],
    "T": ["111", "010", "010", "010", "010"], "U": ["101", "101", "101", "101", "111"],
    "V": ["101", "101", "101", "101", "010"], "Y": ["101", "101", "010", "010", "010"],
    "Z": ["111", "001", "010", "100", "111"], "0": ["111", "101", "101", "101", "111"],
    "1": ["010", "110", "010", "010", "111"], "2": ["110", "001", "010", "100", "111"],
    "3": ["110", "001", "010", "001", "110"], "4": ["101", "101", "111", "001", "001"],
    "5": ["111", "100", "110", "001", "110"], "6": ["011", "100", "111", "101", "111"],
    "7": ["111", "001", "010", "010", "010"], "8": ["111", "101", "111", "101", "111"],
    "9": ["111", "101", "111", "001", "110"], "-": ["000", "000", "111", "000", "000"],
    ".": ["000", "000", "000", "000", "010"], "/": ["001", "001", "010", "100", "100"],
    " ": ["000", "000", "000", "000", "000"], "α": ["000", "011", "101", "101", "011"],
}


def text(img, x, y, s, color):
    px = img.load()
    for ch in s.upper():
        glyph = FONT.get(ch, FONT[" "])
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == "1" and 0 <= x + gx < img.width and 0 <= y + gy < img.height:
                    px[x + gx, y + gy] = color
        x += 4
    return x


# ----------------------------------------------------------------------------- flat item icons (16x16)

def icon(draw_fn, name):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    draw_fn(img, d)
    save_png(img, "textures/item", name + ".png")
    save_json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{name}"}},
              "models/item", name + ".json")


def outline_rect(d, box, fill, edge):
    d.rectangle(box, fill=fill, outline=edge)


def draw_optical_glass(img, d):
    d.polygon([(3, 2), (12, 2), (13, 13), (2, 13)], fill=rgba("#bfe6f5", 200), outline=rgba("#6aa9c4"))
    d.line([(5, 4), (8, 4)], fill=rgba("#ffffff"))
    d.line([(4, 6), (5, 5)], fill=rgba("#ffffff"))


def lens_disc(color, edge):
    def f(img, d):
        d.ellipse([2, 3, 13, 12], fill=color, outline=edge)
        d.ellipse([4, 5, 7, 7], fill=rgba("#ffffff", 220))
        d.line([(3, 8), (12, 8)], fill=shade(edge, 1.2))
    return f


def draw_wafer(img, d):
    d.ellipse([1, 1, 14, 14], fill=rgba("#7d8a9e"), outline=rgba("#3d4656"))
    for i in range(3, 14, 3):
        d.line([(i, 2), (i, 13)], fill=rgba("#5b6577"))
        d.line([(2, i), (13, i)], fill=rgba("#5b6577"))
    d.point((4, 4), fill=rgba("#e0e8ff"))


def sensor(core):
    def f(img, d):
        outline_rect(d, [1, 2, 14, 13], rgba("#2f6b3a"), rgba("#1c3f22"))
        for x in range(2, 14, 2):
            d.point((x, 2), fill=rgba("#e8c547"))
            d.point((x, 13), fill=rgba("#e8c547"))
        outline_rect(d, [4, 5, 11, 10], core, rgba("#111111"))
        d.line([(5, 6), (7, 6)], fill=shade(core, 1.6))
    return f


def draw_processor(img, d):
    outline_rect(d, [2, 2, 13, 13], rgba("#262626"), rgba("#111111"))
    for i in range(3, 13, 2):
        for p in ((i, 1), (i, 14), (1, i), (14, i)):
            d.point(p, fill=rgba("#c8c8c8"))
    outline_rect(d, [5, 5, 10, 10], rgba("#9aa0a6"), rgba("#6b7075"))
    d.point((6, 6), fill=rgba("#ffffff"))


def draw_board(img, d):
    outline_rect(d, [1, 3, 14, 12], rgba("#2e7d32"), rgba("#1b5e20"))
    d.line([(2, 5), (8, 5), (8, 9), (13, 9)], fill=rgba("#e0b84a"))
    d.line([(3, 11), (6, 7)], fill=rgba("#e0b84a"))
    d.rectangle([10, 4, 12, 6], fill=rgba("#202020"))


def draw_display(img, d):
    outline_rect(d, [1, 3, 14, 12], rgba("#1a1a1a"), rgba("#000000"))
    d.rectangle([2, 4, 13, 11], fill=rgba("#2a5fb0"))
    d.line([(3, 5), (6, 5)], fill=rgba("#9cc4ff"))
    d.line([(3, 6), (4, 6)], fill=rgba("#9cc4ff"))


def draw_shutter(img, d):
    outline_rect(d, [1, 2, 14, 13], rgba("#8d9196"), rgba("#55595e"))
    for y in range(4, 12, 2):
        d.line([(3, y), (12, y)], fill=rgba("#1e1e1e"))
        d.line([(3, y + 1), (12, y + 1)], fill=rgba("#353535"))


def draw_ingot(color):
    def f(img, d):
        d.polygon([(2, 10), (5, 5), (14, 5), (11, 10)], fill=color, outline=shade(color, 0.6))
        d.polygon([(2, 10), (11, 10), (11, 12), (2, 12)], fill=shade(color, 0.75), outline=shade(color, 0.55))
        d.line([(6, 6), (12, 6)], fill=shade(color, 1.25))
    return f


def draw_plastic(img, d):
    for (x, y) in [(3, 4), (8, 3), (11, 7), (5, 9), (9, 11), (3, 12)]:
        d.ellipse([x, y, x + 3, y + 2], fill=rgba("#2b2b2b"), outline=rgba("#111111"))
        d.point((x + 1, y), fill=rgba("#6a6a6a"))


def draw_blades(img, d):
    cx, cy = 7.5, 7.5
    pts = [(cx + 6.5 * math.cos(a), cy + 6.5 * math.sin(a)) for a in np.linspace(0, 2 * math.pi, 8, endpoint=False)]
    d.polygon(pts, fill=rgba("#3a3a3a"), outline=rgba("#1a1a1a"))
    inner = [(cx + 2.5 * math.cos(a + 0.4), cy + 2.5 * math.sin(a + 0.4)) for a in np.linspace(0, 2 * math.pi, 8, endpoint=False)]
    for p, q in zip(pts, inner):
        d.line([p, q], fill=rgba("#5c5c5c"))
    d.polygon(inner, fill=rgba("#0d0d0d"))


def draw_motor(img, d):
    outline_rect(d, [3, 4, 12, 11], rgba("#b87333"), rgba("#7a4a1f"))
    for x in range(4, 12, 2):
        d.line([(x, 4), (x, 11)], fill=rgba("#d4904f"))
    d.rectangle([12, 7, 14, 8], fill=rgba("#bbbbbb"))


def draw_stabilizer(img, d):
    outline_rect(d, [1, 1, 14, 14], rgba("#3b3f45"), rgba("#1f2226"))
    d.ellipse([4, 4, 11, 11], outline=rgba("#d0d4d8"))
    d.ellipse([6, 6, 9, 9], fill=rgba("#7fb3ff"))
    d.line([(7, 2), (7, 4)], fill=rgba("#d0d4d8"))


def draw_cell(img, d):
    outline_rect(d, [5, 2, 10, 14], rgba("#2c5aa0"), rgba("#173563"))
    d.rectangle([6, 1, 9, 2], fill=rgba("#c0c0c0"))
    d.line([(6, 4), (6, 12)], fill=rgba("#5d8fd6"))


def draw_flash(img, d):
    outline_rect(d, [3, 4, 12, 11], rgba("#1f1f1f"), rgba("#000000"))
    for x in range(4, 12, 2):
        d.point((x, 3), fill=rgba("#c8c8c8"))
        d.point((x, 12), fill=rgba("#c8c8c8"))
    d.line([(5, 6), (8, 6)], fill=rgba("#777777"))


def cartridge(color):
    def f(img, d):
        outline_rect(d, [3, 2, 12, 13], rgba("#e8e8e8"), rgba("#8a8a8a"))
        d.rectangle([4, 4, 11, 9], fill=color)
        d.rectangle([5, 11, 10, 12], fill=rgba("#555555"))
        d.line([(5, 5), (7, 5)], fill=shade(color, 1.4))
    return f


def draw_paper(img, d):
    for i, c in enumerate(("#d9d5cc", "#e9e6df", "#ffffff")):
        d.rectangle([2 + i, 4 - i, 12 + i, 12 - i], fill=rgba(c), outline=rgba("#b5b0a5"))
    d.rectangle([5, 4, 12, 9], fill=rgba("#9ec3e6"))
    d.polygon([(5, 9), (8, 6), (10, 8), (12, 6), (12, 9)], fill=rgba("#5c8f4a"))


def draw_frame_icon(img, d):
    outline_rect(d, [1, 2, 14, 13], rgba("#9c7a4a"), rgba("#5e4628"))
    d.rectangle([3, 4, 12, 11], fill=rgba("#f2f0ea"))
    d.rectangle([4, 5, 11, 10], fill=rgba("#87b5e0"))
    d.polygon([(4, 10), (7, 7), (9, 9), (11, 6), (11, 10)], fill=rgba("#4f7f3f"))


def draw_camera_icon(img, d):
    outline_rect(d, [1, 4, 14, 12], rgba("#1e1e1e"), rgba("#000000"))
    d.rectangle([4, 2, 8, 4], fill=rgba("#1e1e1e"))
    d.ellipse([4, 5, 10, 11], fill=rgba("#2d2d2d"), outline=rgba("#a0a0a0"))
    d.ellipse([6, 7, 8, 9], fill=rgba("#3b4f7a"))
    d.point((6, 7), fill=rgba("#ffffff"))
    d.rectangle([11, 5, 13, 6], fill=rgba("#e0e0e0"))


def draw_lens_icon(img, d):
    outline_rect(d, [3, 2, 12, 13], rgba("#1c1c1c"), rgba("#000000"))
    for y in range(4, 8):
        d.line([(3, y), (12, y)], fill=rgba("#2c2c2c") if y % 2 else rgba("#111111"))
    d.ellipse([4, 9, 11, 13], fill=rgba("#2c3e66"), outline=rgba("#8a8a8a"))
    d.point((6, 10), fill=rgba("#ffffff"))


def battery_icon(body, label):
    def f(img, d):
        outline_rect(d, [3, 1, 12, 14], body, shade(body, 0.5))
        d.rectangle([5, 0, 7, 1], fill=rgba("#c9a54a"))
        d.rectangle([8, 0, 10, 1], fill=rgba("#c9a54a"))
        d.rectangle([4, 5, 11, 7], fill=label)
        d.line([(4, 10), (11, 10)], fill=shade(body, 1.4))
    return f


def card_icon(body, stripe):
    def f(img, d):
        d.polygon([(3, 2), (10, 2), (12, 4), (12, 13), (3, 13)], fill=body, outline=shade(body, 0.5))
        for x in range(4, 11, 2):
            d.line([(x, 3), (x, 5)], fill=rgba("#d9b44a"))
        d.rectangle([4, 8, 11, 11], fill=stripe)
    return f


def draw_flash_icon(img, d):
    outline_rect(d, [5, 6, 10, 14], rgba("#222222"), rgba("#000000"))
    outline_rect(d, [3, 1, 12, 6], rgba("#2c2c2c"), rgba("#000000"))
    d.rectangle([4, 2, 11, 4], fill=rgba("#f0f0ea"))


def filter_generic(img, d):
    d.ellipse([1, 1, 14, 14], fill=rgba("#1c1c1c"))
    d.ellipse([3, 3, 12, 12], fill=rgba("#7090c0", 120))
    d.line([(5, 5), (7, 4)], fill=rgba("#ffffff", 200))


def draw_grip_icon(img, d):
    outline_rect(d, [3, 2, 12, 13], rgba("#222222"), rgba("#000000"))
    d.rectangle([9, 2, 12, 13], fill=rgba("#141414"))
    d.rectangle([10, 4, 11, 5], fill=rgba("#c03020"))


def draw_tripod_icon(img, d):
    d.line([(7, 3), (2, 15)], fill=rgba("#2a2a2a"), width=1)
    d.line([(8, 3), (8, 15)], fill=rgba("#2a2a2a"), width=1)
    d.line([(8, 3), (13, 15)], fill=rgba("#2a2a2a"), width=1)
    d.rectangle([5, 1, 10, 3], fill=rgba("#444444"))


def draw_bag_icon(img, d):
    outline_rect(d, [2, 5, 13, 14], rgba("#3d3326"), rgba("#1f1a12"))
    d.arc([5, 1, 10, 8], 180, 360, fill=rgba("#1f1a12"))
    d.rectangle([4, 8, 11, 9], fill=rgba("#c0a060"))


def draw_kit_icon(img, d):
    d.ellipse([2, 6, 8, 13], fill=rgba("#2a2a2a"))
    d.rectangle([4, 2, 6, 6], fill=rgba("#555555"))
    d.rectangle([9, 4, 13, 13], fill=rgba("#e8e8e0"), outline=rgba("#a0a098"))


# ----------------------------------------------------------------------------- 3D model building

def face_uv(region, scale=0.25):
    """Texture pixel box -> model UV (0..16 maps to a 64 px texture)."""
    x0, y0, x1, y1 = region
    return [x0 * scale, y0 * scale, x1 * scale, y1 * scale]


def element(frm, to, faces, rotation=None, shade_=True):
    e = {"from": [round(v, 3) for v in frm], "to": [round(v, 3) for v in to], "faces": faces}
    if rotation:
        e["rotation"] = rotation
    if not shade_:
        e["shade"] = False
    return e


def all_faces(region, overrides=None, tex="#t"):
    faces = {f: {"uv": face_uv(region), "texture": tex} for f in ("north", "south", "east", "west", "up", "down")}
    for k, v in (overrides or {}).items():
        faces[k] = {"uv": face_uv(v), "texture": tex}
    return faces


def cylinder(cx, cy, z0, z1, r, side_region, cap_front=None, cap_back=None, tex="#t"):
    """Regular 16-sided prism along z (apothem r).

    Built from 8 thin "spokes" through the axis, one per pair of opposite sides, at 22.5° steps. Minecraft only
    rotates elements by -45..45° in 22.5° steps, so the spokes at 67.5°..157.5° are the 0°..-45° ones with
    width and height swapped. A spoke of half-width r·tan(11.25°) exactly fills its wedge of the polygon, so
    the union is the polygon without gaps. Each outer face gets its own 1/16 slice of the side texture (the
    texture wraps around the barrel); the caps of the spokes are staggered by a hair to avoid z-fighting.
    """
    t = r * math.tan(math.radians(11.25))
    x0, y0, x1, y1 = side_region
    slice_w = (x1 - x0) / 16.0

    def side_uv(k):
        u0 = x0 + slice_w * (k % 16)
        return [u0 * 0.25, y0 * 0.25, (u0 + slice_w) * 0.25, y1 * 0.25]

    def cap_uv(region, half_w, half_h):
        if region is None:
            return None
        rx0, ry0, rx1, ry1 = region
        mx, my = (rx0 + rx1) / 2, (ry0 + ry1) / 2
        hw, hh = (rx1 - rx0) / 2 * half_w / r, (ry1 - ry0) / 2 * half_h / r
        return [(mx - hw) * 0.25, (my - hh) * 0.25, (mx + hw) * 0.25, (my + hh) * 0.25]

    els = []
    # (rotation angle, vertical spoke?) -> direction of the spoke's long axis = 90° + angle or 0° + angle
    spokes = [(0, True), (22.5, True), (45, True), (-22.5, True), (-45, True), (0, False), (22.5, False), (-22.5, False)]
    for i, (ang, vertical) in enumerate(spokes):
        hw, hh = (t, r) if vertical else (r, t)
        stagger = i * 0.004
        faces = {}
        if vertical:
            # long axis along y: the up/down faces are outer sides; u runs across the face (around the barrel)
            k_up = round((90 + ang) / 22.5)
            faces["up"] = {"uv": side_uv(k_up), "texture": tex}
            faces["down"] = {"uv": side_uv(k_up + 8), "texture": tex}
        else:
            k_e = round(ang / 22.5)
            faces["east"] = {"uv": side_uv(k_e), "texture": tex, "rotation": 90}
            faces["west"] = {"uv": side_uv(k_e + 8), "texture": tex, "rotation": 90}
        if cap_front:
            faces["north"] = {"uv": cap_uv(cap_front, hw, hh), "texture": tex}
        if cap_back:
            faces["south"] = {"uv": cap_uv(cap_back, hw, hh), "texture": tex}
        rot = {"angle": ang, "axis": "z", "origin": [cx, cy, (z0 + z1) / 2]} if ang else None
        els.append(element([cx - hw, cy - hh, z0 + stagger], [cx + hw, cy + hh, z1 - stagger], faces, rotation=rot))
    return els


ITEM_DISPLAY_3D = {
    "gui": {"rotation": [20, 150, 0], "translation": [0, -0.5, 0], "scale": [0.78, 0.78, 0.78]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 25, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
    "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 1], "scale": [0.45, 0.45, 0.45]},
    "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 1], "scale": [0.45, 0.45, 0.45]},
    "firstperson_righthand": {"rotation": [0, -10, 0], "translation": [2, 1, 0], "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, 10, 0], "translation": [2, 1, 0], "scale": [0.55, 0.55, 0.55]},
    "head": {"rotation": [0, 180, 0], "translation": [0, 13, 7], "scale": [0.8, 0.8, 0.8]},
}


def model(texture, elements, display=ITEM_DISPLAY_3D):
    return {"texture_size": [64, 64], "textures": {"t": texture, "particle": texture},
            "elements": elements, "display": display}


# Camera body layout (model units; 1 unit ≈ 9 mm). Front of the camera faces north (-z).
MOUNT_X, MOUNT_Y, MOUNT_Z = 6.5, 7.0, 9.0


def camera_body_texture(brand_text, accent, model_text="α7", top_color=(40, 40, 42), body_color=(34, 34, 36)):
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    noise_fill(t, (0, 0, 16, 16), body_color, 6, seed=1)          # body plastic/magnesium
    noise_fill(t, (16, 0, 32, 16), (22, 22, 23), 9, seed=2)          # grip rubber
    for y in range(16):
        for x in range(16, 32):
            if (x + y) % 3 == 0:
                t.putpixel((x, y), (16, 16, 17, 255))
    noise_fill(t, (32, 0, 48, 16), body_color, 6, seed=3)          # front with logo
    text(t, 34, 3, brand_text, (235, 235, 235, 255))
    noise_fill(t, (48, 0, 64, 16), (178, 180, 184), 14, seed=4)      # mount ring metal
    for y in range(16):
        t.putpixel((48 + (y * 3) % 16, y), (120, 122, 126, 255))
    # rear: LCD + buttons
    noise_fill(t, (0, 16, 16, 32), (30, 30, 32), 5, seed=5)
    ImageDraw.Draw(t).rectangle([1, 19, 11, 28], fill=(12, 14, 18, 255), outline=(60, 60, 60, 255))
    ImageDraw.Draw(t).line([(2, 20), (5, 20)], fill=(70, 90, 120, 255))
    for p in ((13, 20), (13, 23), (13, 26)):
        t.putpixel(p, (90, 90, 90, 255))
    # EVF eyecup
    noise_fill(t, (16, 16, 32, 32), (18, 18, 19, 8), 6, seed=6)
    ImageDraw.Draw(t).rectangle([19, 19, 28, 28], fill=(5, 5, 6, 255))
    # dial (knurled)
    noise_fill(t, (32, 16, 48, 32), (60, 60, 62), 8, seed=7)
    for x in range(32, 48, 2):
        ImageDraw.Draw(t).line([(x, 16), (x, 31)], fill=(36, 36, 38, 255))
    # accents: red record / shutter button / orange
    noise_fill(t, (48, 16, 64, 32), accent, 6, seed=8)
    # top plate with markings
    noise_fill(t, (0, 32, 16, 48), top_color, 5, seed=9)
    text(t, 1, 34, model_text, (220, 220, 220, 255) if sum(top_color) < 300 else (30, 30, 30, 255))
    # viewfinder windows (rangefinder front)
    noise_fill(t, (32, 32, 48, 48), (25, 30, 40), 4, seed=11)
    ImageDraw.Draw(t).rectangle([34, 36, 38, 40], fill=(110, 130, 160, 255))
    # sensor / mount opening
    noise_fill(t, (16, 32, 32, 48), (8, 8, 9), 3, seed=10)
    ImageDraw.Draw(t).rectangle([20, 36, 28, 44], fill=(40, 30, 70, 255))
    return t


def camera_body_model(texture, style="mirrorless"):
    R = {"body": (0, 0, 16, 16), "grip": (16, 0, 32, 16), "front": (32, 0, 48, 16), "metal": (48, 0, 64, 16),
         "rear": (0, 16, 16, 32), "evf": (16, 16, 32, 32), "dial": (32, 16, 48, 32), "accent": (48, 16, 64, 32),
         "top": (0, 32, 16, 48), "sensor": (16, 32, 32, 48), "window": (32, 32, 48, 48)}
    if style == "dslr":
        return dslr_model(texture, R)
    if style == "rangefinder":
        return rangefinder_model(texture, R)
    if style in BODY_STYLES:
        return model(f"{NS}:item/body/{texture}", BODY_STYLES[style](R))
    els = []
    # main body
    els.append(element([1.5, 3, 9], [14.5, 11.5, 13.5], all_faces(R["body"], {
        "north": R["front"], "south": R["rear"], "up": R["top"]})))
    # grip (right side seen from the back = +x)
    els.append(element([11, 3, 6.8], [14.5, 11, 9], all_faces(R["grip"])))
    # EVF hump
    els.append(element([4, 11.5, 9.6], [9, 13.6, 13.2], all_faces(R["body"], {"up": R["top"]})))
    els.append(element([4.6, 11.7, 13.2], [8.4, 13.4, 14.6], all_faces(R["evf"])))
    # shutter button & dials
    els.append(element([12.2, 11, 7.4], [13.6, 11.6, 8.8], all_faces(R["dial"], {"up": R["accent"]})))
    els.append(element([10, 11.5, 10], [12.6, 12.2, 12.6], all_faces(R["dial"])))
    els.append(element([2, 11.5, 10.2], [3.8, 12.2, 12.2], all_faces(R["dial"])))
    # mount ring + sensor
    els += cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.4, MOUNT_Z, 2.9, R["metal"], cap_front=R["sensor"])
    return model(f"{NS}:item/body/{texture}", els)


def dslr_model(texture, R):
    """Bigger body, deep grip, pentaprism hump over the mount (mirror box)."""
    els = [element([1, 2.5, 9], [15, 11.5, 14], all_faces(R["body"], {"north": R["front"], "south": R["rear"], "up": R["top"]})),
           element([11, 2.5, 6.3], [15, 11, 9], all_faces(R["grip"])),
           # pentaprism: a block plus two 22.5° slopes
           element([3.6, 11.5, 9.4], [9.4, 14.2, 13.4], all_faces(R["body"], {"up": R["top"]})),
           element([4.6, 11.8, 13.4], [8.4, 13.6, 14.8], all_faces(R["evf"])),
           element([12, 11, 6.8], [13.6, 11.7, 8.4], all_faces(R["dial"], {"up": R["accent"]})),
           element([10.2, 11.5, 10], [13.4, 12.3, 13], all_faces(R["top"])),
           element([1.6, 11.5, 10], [3.4, 12.4, 12.4], all_faces(R["dial"]))]
    els += cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.5, MOUNT_Z, 3.0, R["metal"], cap_front=R["sensor"])
    return model(f"{NS}:item/body/{texture}", els)


def rangefinder_model(texture, R):
    """Flat-top classic: no grip, no hump, viewfinder windows on the front."""
    els = [element([1, 3.5, 9.6], [15, 10.8, 12.8], all_faces(R["body"], {"north": R["front"], "south": R["rear"], "up": R["top"]})),
           element([1.6, 10.8, 9.8], [14.4, 11.6, 12.6], all_faces(R["top"])),
           element([10.4, 8.4, 9.4], [13.2, 10.2, 9.6], all_faces(R["window"])),
           element([2.2, 8.6, 9.4], [3.6, 9.8, 9.6], all_faces(R["window"])),
           element([11.6, 11.6, 10.4], [13.2, 12.4, 12], all_faces(R["dial"], {"up": R["accent"]})),
           element([3, 11.6, 10.4], [5, 12.2, 12], all_faces(R["dial"]))]
    els += cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.3, MOUNT_Z + 0.6, 2.7, R["metal"], cap_front=R["sensor"])
    return model(f"{NS}:item/body/{texture}", els)


def _faces(R, base, **over):
    return all_faces(R[base], {k: R[v] for k, v in over.items()})


def flat_body(R):
    """Rangefinder-style mirrorless (α6400, ZV-E10): flat top, EVF in the rear corner."""
    els = [element([1.5, 3.5, 9], [14.5, 10.5, 13.2], _faces(R, "body", north="front", south="rear", up="top")),
           element([11, 3.5, 7], [14.5, 10, 9], _faces(R, "grip")),
           element([1.8, 8.6, 13.2], [5.4, 10.3, 14.3], _faces(R, "evf")),
           element([12.2, 10.5, 7.6], [13.6, 11.1, 8.8], _faces(R, "dial", up="accent")),
           element([10, 10.5, 10], [12.6, 11.2, 12.6], _faces(R, "dial"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.4, MOUNT_Z, 2.9, R["metal"], cap_front=R["sensor"])


def retro_body(R):
    """Mirrorless with a centre hump, a light top plate and dials (Zf, X-T5, X-T30)."""
    els = [element([1.5, 3, 9], [14.5, 9.6, 13.2], _faces(R, "body", north="front", south="rear")),
           element([1.5, 9.6, 9], [14.5, 11, 13.2], _faces(R, "top")),
           element([11.8, 3, 8], [14.5, 9.4, 9], _faces(R, "grip")),
           element([4.2, 11, 9.4], [8.8, 13.6, 13], _faces(R, "top")),
           element([5, 11.4, 13], [8, 13.2, 14.2], _faces(R, "evf")),
           element([10, 11, 10], [12.4, 12.4, 12.4], _faces(R, "dial")),
           element([2, 11, 10], [4.2, 12.4, 12.2], _faces(R, "dial")),
           element([12.6, 11, 8.6], [13.6, 12.6, 9.6], _faces(R, "dial", up="accent"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.4, MOUNT_Z, 2.9, R["metal"], cap_front=R["sensor"])


def retro_slr_body(R):
    """Classic film SLR: thin leatherette body, chrome top plate, pentaprism, knobs and an advance lever."""
    els = [element([1, 4, 9.4], [15, 9.8, 12.6], _faces(R, "body", north="front", south="rear")),
           element([1, 9.8, 9.4], [15, 11, 12.6], _faces(R, "top")),
           element([4.3, 11, 9.6], [8.7, 13.2, 12.4], _faces(R, "top")),
           element([4.8, 13.2, 9.9], [8.2, 13.8, 12.1], _faces(R, "top")),
           element([5.2, 10.8, 12.6], [7.8, 12.6, 13.4], _faces(R, "evf")),
           element([1.8, 11, 10.2], [3.6, 12.2, 12], _faces(R, "dial")),
           element([10.4, 11, 10.2], [12.6, 11.8, 12.2], _faces(R, "dial")),
           element([12.6, 11, 10.6], [14.6, 11.4, 11.8], _faces(R, "top")),
           element([11.2, 11.8, 10.8], [11.8, 12.2, 11.4], _faces(R, "accent"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.3, MOUNT_Z + 0.4, 2.6, R["metal"], cap_front=R["sensor"])


def compact_body(R):
    """Compact with a built-in lens (X100VI, Q3, T2): slim box, top plate, finder window."""
    els = [element([1.5, 4, 9], [14.5, 10.4, 12.6], _faces(R, "body", north="front", south="rear")),
           element([1.5, 10.4, 9], [14.5, 11.2, 12.6], _faces(R, "top")),
           element([10.8, 9, 8.9], [13.6, 10.2, 9], _faces(R, "window")),
           element([10.6, 11.2, 10], [12.6, 12, 12], _faces(R, "dial")),
           element([2.4, 11.2, 10], [4.2, 12, 11.8], _faces(R, "dial")),
           element([12.8, 11.2, 9.6], [13.8, 11.8, 10.6], _faces(R, "accent")),
           element([12.2, 4.5, 8.4], [14.2, 9.8, 9], _faces(R, "grip"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.3, MOUNT_Z, 2.7, R["metal"], cap_front=R["sensor"])


def pro_body(R):
    """Flagship with an integrated vertical grip (Z9, R1)."""
    els = [element([1, 0.6, 9], [15, 11.5, 14], _faces(R, "body", north="front", south="rear", up="top")),
           element([11, 3, 6.3], [15, 11, 9], _faces(R, "grip")),
           element([10.6, 0.6, 6.6], [15, 3, 9], _faces(R, "grip")),
           element([3.6, 11.5, 9.4], [9.4, 14.4, 13.4], _faces(R, "body", up="top")),
           element([4.6, 11.8, 13.4], [8.4, 13.6, 14.8], _faces(R, "evf")),
           element([10.2, 11.5, 10], [13.6, 12.1, 13], _faces(R, "top")),
           element([12, 11, 6.8], [13.6, 11.7, 8.4], _faces(R, "dial", up="accent")),
           element([1.6, 11.5, 10], [3.4, 12.4, 12.4], _faces(R, "dial"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.5, MOUNT_Z, 3.0, R["metal"], cap_front=R["sensor"])


def big_body(R):
    """Medium-format mirrorless (GFX100 II, X2D): a larger body around a wide mount."""
    els = [element([0.5, 2, 9], [15.5, 12, 14.2], _faces(R, "body", north="front", south="rear", up="top")),
           element([11, 2, 5.8], [15.5, 11.5, 9], _faces(R, "grip")),
           element([3.8, 12, 9.6], [9.2, 14.8, 13.6], _faces(R, "body", up="top")),
           element([4.8, 12.3, 13.6], [8.2, 14.4, 15], _faces(R, "evf")),
           element([11, 12, 10], [13.8, 12.8, 12.8], _faces(R, "dial")),
           element([12.4, 11.5, 6.4], [14, 12.2, 8], _faces(R, "dial", up="accent"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.5, MOUNT_Z, 3.4, R["metal"], cap_front=R["sensor"])


def medium_box_body(R):
    """Hasselblad V: a cube with a film magazine at the back and a waist-level finder on top."""
    els = [element([0.6, 1, 9], [12.4, 13, 15.8], _faces(R, "body", north="front", up="top")),
           element([0.4, 0.6, 15.8], [12.6, 13.4, 17.6], _faces(R, "evf", east="body", west="body")),
           element([1.2, 13, 9.4], [11.8, 15.2, 15.4], _faces(R, "top")),
           element([12.4, 5.6, 11], [13.8, 8.4, 13.8], _faces(R, "dial")),
           element([9.8, 2, 8.6], [11, 3.2, 9], _faces(R, "accent"))]
    return els + cylinder(MOUNT_X, MOUNT_Y, MOUNT_Z - 0.4, MOUNT_Z, 3.3, R["metal"], cap_front=R["sensor"])


BODY_STYLES = {"flat": flat_body, "retro": retro_body, "retro_slr": retro_slr_body, "compact": compact_body,
               "pro": pro_body, "big": big_body, "medium_box": medium_box_body}


def ring_part(name, folder, length_units, radius, colour):
    """Mount adapter / teleconverter: a short metal tube (standalone and mounted versions)."""
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    noise_fill(t, (0, 0, 32, 16), colour, 8, seed=31)
    noise_fill(t, (32, 32, 64, 48), (178, 180, 184), 14, seed=32)
    noise_fill(t, (0, 32, 32, 64), (10, 10, 12), 3, seed=33)
    ImageDraw.Draw(t).line([(0, 8), (31, 8)], fill=(200, 200, 200, 255))
    save_png(t, f"textures/item/{folder}", name + ".png")
    tex = f"{NS}:item/{folder}/{name}"
    R = {"side": (0, 0, 32, 16), "metal": (32, 32, 64, 48), "dark": (0, 32, 32, 64)}

    def build(cx, cy, z_back):
        els = cylinder(cx, cy, z_back - length_units, z_back, radius, R["side"], cap_front=R["dark"], cap_back=R["dark"])
        els += cylinder(cx, cy, z_back - 0.4, z_back, radius * 0.8, R["metal"])
        return els
    save_json(model(tex, build(8, 8, 8 + length_units / 2), lens_display(length_units, radius)), f"models/item/{folder}", name + ".json")
    save_json(model(tex, build(MOUNT_X, MOUNT_Y, MOUNT_Z)), f"models/item/{folder}", name + "_mounted.json")


def flash_part(name, colour, head_text):
    """Hot-shoe speedlight: flat icon + a model standing on the camera's top (mounted)."""
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    noise_fill(t, (0, 0, 32, 16), colour, 5, seed=41)
    noise_fill(t, (32, 0, 64, 16), (235, 235, 230), 4, seed=42)      # diffuser window
    noise_fill(t, (0, 16, 32, 32), (20, 20, 22), 4, seed=43)          # rear panel
    ImageDraw.Draw(t).rectangle([4, 19, 20, 27], fill=(30, 60, 50, 255))
    save_png(t, "textures/item/flash", name + ".png")
    tex = f"{NS}:item/flash/{name}"
    R = {"body": (0, 0, 32, 16), "window": (32, 0, 64, 16), "rear": (0, 16, 32, 32)}

    def build(x, y, z):
        foot = element([x - 1.2, y, z - 1.2], [x + 1.2, y + 0.6, z + 1.2], all_faces(R["rear"]))
        body = element([x - 1.6, y + 0.6, z - 1.4], [x + 1.6, y + 4.6, z + 1.6], all_faces(R["body"], {"south": R["rear"]}))
        head = element([x - 2.0, y + 4.6, z - 2.2], [x + 2.0, y + 6.6, z + 1.0],
                       all_faces(R["body"], {"north": R["window"]}))
        return [foot, body, head]
    save_json(model(tex, build(8, 4.5, 8), LENS_DISPLAY), "models/item/flash", name + ".json")
    save_json(model(tex, build(6.5, 13.6, 11.2)), "models/item/flash", name + "_mounted.json")


def grip_part(name, colour):
    """Vertical battery grip under the body."""
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    noise_fill(t, (0, 0, 32, 16), colour, 6, seed=51)
    noise_fill(t, (32, 0, 64, 16), (18, 18, 19), 9, seed=52)
    save_png(t, "textures/item/grip", name + ".png")
    tex = f"{NS}:item/grip/{name}"
    R = {"body": (0, 0, 32, 16), "rubber": (32, 0, 64, 16)}

    def build(y_top):
        return [element([1.5, y_top - 3.0, 9.0], [14.5, y_top, 13.5], all_faces(R["body"])),
                element([11.0, y_top - 3.0, 6.8], [14.5, y_top, 9.0], all_faces(R["rubber"]))]
    save_json(model(tex, [e for e in build(9.5)], LENS_DISPLAY), "models/item/grip", name + ".json")
    save_json(model(tex, build(3.0)), "models/item/grip", name + "_mounted.json")


# Lens finishes: barrel colour, ring rubber (light/dark rib shades), accent ring colour (None = none) and
# where it sits, aperture ring next to the mount (manual and Fujifilm primes), focus ring metal.
LENS_LOOKS = {
    "black": dict(barrel=(26, 26, 27), ribs=(44, 18), accent=None),
    "sony_gm": dict(barrel=(26, 26, 27), ribs=(44, 18), accent=(232, 118, 30), accent_at="mid"),
    "sony_gm_white": dict(barrel=(222, 222, 218), ribs=(44, 18), accent=(232, 118, 30), accent_at="mid"),
    "nikon_s": dict(barrel=(28, 28, 29), ribs=(46, 20), accent=(176, 178, 182), accent_at="front"),
    "nikon_gold": dict(barrel=(26, 26, 27), ribs=(44, 18), accent=(205, 170, 70), accent_at="front"),
    "canon_l": dict(barrel=(26, 26, 27), ribs=(44, 18), accent=(200, 30, 30), accent_at="front"),
    "canon_l_white": dict(barrel=(232, 232, 228), ribs=(44, 18), accent=(200, 30, 30), accent_at="front"),
    "vintage": dict(barrel=(22, 22, 22), ribs=(150, 40), accent=(196, 198, 202), accent_at="front", aperture=True),
    "fuji": dict(barrel=(28, 28, 29), ribs=(46, 20), accent=None, aperture=True),
    "silver": dict(barrel=(190, 190, 194), ribs=(44, 18), accent=None),
    "chrome": dict(barrel=(196, 198, 202), ribs=(176, 112), accent=(30, 30, 30), accent_at="front", aperture=True),
    "chrome_black": dict(barrel=(24, 24, 24), ribs=(190, 120), accent=(196, 198, 202), accent_at="front", aperture=True),
    "hasselblad": dict(barrel=(70, 70, 72), ribs=(44, 18), accent=(150, 150, 154), accent_at="front"),
    "silver_ring": dict(barrel=(26, 26, 27), ribs=(44, 18), accent=(182, 182, 186), accent_at="mount"),
    "sigma": dict(barrel=(24, 24, 25), ribs=(44, 18), accent=(182, 182, 186), accent_at="mid"),
    "titanium": dict(barrel=(170, 160, 140), ribs=(150, 120), accent=None),
}

LENS_R = {"barrel": (0, 0, 32, 16), "label": (32, 0, 64, 16), "zoom": (0, 16, 32, 32), "focus": (32, 16, 64, 32),
          "glass": (0, 32, 32, 64), "metal": (32, 32, 64, 48), "rear": (32, 48, 48, 64),
          "accent": (48, 48, 56, 56), "aperture": (56, 48, 64, 56), "dark": (48, 56, 56, 64), "print": (56, 56, 64, 64)}


def lens_texture(label, look="black"):
    L = LENS_LOOKS[look]
    barrel = L["barrel"]
    light = sum(barrel) > 300
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(t)
    noise_fill(t, (0, 0, 32, 16), barrel, 4, seed=11)        # barrel
    noise_fill(t, (32, 0, 64, 16), barrel, 4, seed=12)       # barrel with printing
    # printing as short dashes in a few slices only (the side texture wraps around the barrel in 16 slices)
    ink = (40, 40, 40, 255) if light else (210, 210, 210, 255)
    for i, ch in enumerate(label[:4]):
        d.point((38 + (i % 2), 5 + 2 * (i // 2)), fill=ink)
        d.point((40 + (i % 2), 6 + 2 * (i // 2)), fill=ink)
    # zoom ring: coarse rubber ribs (one light and one dark band per 2-pixel slice)
    hi, lo = L["ribs"]
    for x in range(0, 32):
        c = hi if x % 2 == 0 else lo
        tint = 0 if hi < 100 else 2
        d.line([(x, 16), (x, 31)], fill=(c, c, c + tint, 255))
    for x in range(32, 64):
        c = int(hi * 0.9) if x % 2 == 0 else int(lo * 1.1)
        d.line([(x, 16), (x, 31)], fill=(c, c, c, 255))
    # front: black name ring around coated glass (radially symmetric: the cap is drawn by rotated elements)
    for y in range(32, 64):
        for x in range(0, 32):
            dx, dy = (x - 15.5) / 16, (y - 47.5) / 16
            r = math.hypot(dx, dy)
            if r > 0.84:
                base = np.array([20, 20, 21])
                if 0.9 < r < 0.95:
                    base = np.array([70, 70, 72])
            else:
                base = np.array([16, 20, 44]) * (1.25 - r * 0.7)
                if 0.5 < r < 0.58:
                    base = np.array([96, 62, 140])
                if 0.25 < r < 0.3:
                    base = np.array([50, 110, 90])
                if r < 0.1:
                    base = np.array([200, 210, 240])
            t.putpixel((x, y), tuple(int(max(0, min(255, v))) for v in base) + (255,))
    noise_fill(t, (32, 32, 64, 48), (178, 180, 184), 14, seed=13)   # mount metal
    noise_fill(t, (32, 48, 48, 64), (14, 14, 15), 4, seed=14)        # rear cap
    noise_fill(t, (48, 48, 56, 56), L["accent"] or barrel, 3, seed=15)
    # aperture ring: barrel colour with a white index dash every other slice
    noise_fill(t, (56, 48, 64, 56), shade_rgb(barrel, 0.85), 3, seed=16)
    for x in range(56, 64, 2):
        d.line([(x, 50), (x, 53)], fill=(40, 40, 40, 255) if light else (225, 225, 225, 255))
    noise_fill(t, (48, 56, 56, 64), (16, 16, 17), 3, seed=17)        # dark ring / hood
    noise_fill(t, (56, 56, 64, 64), (225, 225, 225), 3, seed=18)
    return t


def shade_rgb(c, f):
    return tuple(int(max(0, min(255, v * f))) for v in c)


def lens_elements(cx, cy, z_back, length, radius, R, zoom=True, look="black", kind="prime"):
    """Lens built from the mount (z_back) forward (towards -z).

    The barrel is a stack of coaxial cylinders of different radii. Where the radius steps up or down the wider
    cylinder needs a cap on that side, or the ring-shaped step is a hole you can look through (the prism has
    no inner faces). `close_steps` adds those caps once the stack is known.
    """
    L = LENS_LOOKS[look]
    segs = []  # [z_front, z_back, r, side, cap_front, cap_back]

    def seg(z0, z1, r, side, cap_front=None, cap_back=None):
        segs.append([z0, z1, r, side, cap_front, cap_back])

    # mount flange
    seg(z_back - 0.6, z_back, radius * 0.72, R["metal"], cap_back=R["rear"])
    z = z_back - 0.6
    if L.get("accent_at") == "mount":
        seg(z - 0.3, z, radius * 0.93, R["accent"])
        z -= 0.3
    seg_len = z - (z_back - length)
    aperture = L.get("aperture") and not zoom
    ap_len = min(seg_len * 0.18, 1.2) if aperture else 0
    if aperture:
        seg(z - ap_len, z, radius * 0.94, R["aperture"])
        z -= ap_len
        seg_len -= ap_len
    zoom_len = seg_len * 0.32 if zoom else 0
    focus_len = seg_len * (0.22 if zoom else 0.42)
    barrel_len = seg_len - zoom_len - focus_len
    seg(z - barrel_len * 0.55, z, radius * 0.92, R["label"])
    z -= barrel_len * 0.55
    if zoom:
        seg(z - zoom_len, z, radius, R["zoom"])
        z -= zoom_len
    if L.get("accent_at") == "mid":
        seg(z - 0.25, z, radius * 0.96, R["accent"])
        z -= 0.25
        barrel_len -= 0.25 / 0.45
    rest = barrel_len * 0.45
    seg(z - rest, z, radius * 0.95, R["barrel"])
    z -= rest
    front_ring = L.get("accent_at") == "front"
    f_len = focus_len - (0.3 if front_ring else 0)
    extra = []
    if kind == "fisheye":
        # short focus ring, then the bulging front element inside a petal hood
        seg(z - f_len, z, radius, R["focus"], cap_front=R["dark"])
        z -= f_len
        seg(z - 0.9, z, radius * 0.62, R["glass"], cap_front=R["glass"])
        for i in range(4):
            ang = i * math.pi / 2 + math.pi / 4
            px, py = cx + math.cos(ang) * radius * 0.8, cy + math.sin(ang) * radius * 0.8
            extra.append(element([px - radius * 0.28, py - radius * 0.28, z - 0.7], [px + radius * 0.28, py + radius * 0.28, z],
                                 all_faces(R["dark"])))
    else:
        seg(z - f_len, z, radius, R["focus"], cap_front=None if front_ring else R["glass"])
        z -= f_len
        if front_ring:
            seg(z - 0.3, z, radius * 1.01, R["accent"], cap_front=R["glass"])
    close_steps(segs, R)
    els = []
    for z0, z1, r, side, cf, cb in segs:
        els += cylinder(cx, cy, z0, z1, r, side, cap_front=cf, cap_back=cb)
    return els + extra


def close_steps(segs, R):
    """Cap the wider cylinder at every radius step of a coaxial stack (segs ordered back to front)."""
    def step_cap(side):
        # ribbed rubber rings would show their ribs as stripes across the cap: give them a plain dark edge
        return R["dark"] if side in (R["zoom"], R["focus"]) else side
    for back, front in zip(segs, segs[1:]):
        if front[2] > back[2] + 1e-6 and front[5] is None:
            front[5] = step_cap(front[3])
        elif back[2] > front[2] + 1e-6 and back[4] is None:
            back[4] = step_cap(back[3])
    if segs[-1][4] is None:
        segs[-1][4] = R["glass"]
    if segs[0][5] is None:
        segs[0][5] = R["rear"]


LENS_DISPLAY = {
    "gui": {"rotation": [25, -40, 0], "translation": [0, 0, 0], "scale": [0.75, 0.75, 0.75]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 125, 0], "translation": [0, 0, 0], "scale": [0.75, 0.75, 0.75]},
    "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
    "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
    "firstperson_righthand": {"rotation": [0, -30, 0], "translation": [1, 2, 0], "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, 30, 0], "translation": [1, 2, 0], "scale": [0.55, 0.55, 0.55]},
}

MAX_LENS_UNITS = 24.5  # model elements must stay inside -16..32; the longest teles are shortened a little


def lens_display(length, radius):
    """Long lenses are drawn smaller in the inventory and in hand so they fit the slot."""
    fit = 13.0 / max(length, 2 * radius, 1)
    out = json.loads(json.dumps(LENS_DISPLAY))
    for key, v in out.items():
        if key == "ground":
            continue
        # small parts (adapters, pancakes) are drawn bigger in slots and frames, never bigger in hand
        k = min(1.8, fit) if key in ("gui", "fixed") else min(1.0, fit)
        v["scale"] = [round(x * k, 3) for x in v["scale"]]
    return out


def make_lens(name, label, length, radius, zoom, gm=False, ring=None, look=None, kind="prime"):
    look = look or ("sony_gm" if gm else "black")
    length = min(length, MAX_LENS_UNITS)
    save_png(lens_texture(label, look), "textures/item/lens", name + ".png")
    tex = f"{NS}:item/lens/{name}"
    # standalone: centred in the item box
    standalone = lens_elements(8, 8, 8 + length / 2, length, radius, LENS_R, zoom, look, kind)
    save_json(model(tex, standalone, lens_display(length, radius)), "models/item/lens", name + ".json")
    # mounted: rear flange on the body's mount face; the body's display transforms apply
    mounted = lens_elements(MOUNT_X, MOUNT_Y, MOUNT_Z, length, radius, LENS_R, zoom, look, kind)
    save_json(model(tex, mounted), "models/item/lens", name + "_mounted.json")


# ----------------------------------------------------------------------------- blocks

def block_textures():
    # assembly station: wooden workbench with a cutting mat and tools
    top = Image.new("RGBA", (16, 16))
    noise_fill(top, (0, 0, 16, 16), (148, 112, 70), 10, seed=21)
    ImageDraw.Draw(top).rectangle([2, 2, 13, 13], fill=(46, 92, 64, 255))
    for i in range(3, 13, 3):
        ImageDraw.Draw(top).line([(i, 2), (i, 13)], fill=(70, 120, 88, 255))
        ImageDraw.Draw(top).line([(2, i), (13, i)], fill=(70, 120, 88, 255))
    ImageDraw.Draw(top).ellipse([8, 3, 12, 7], fill=(150, 190, 220, 255), outline=(30, 30, 30, 255))
    ImageDraw.Draw(top).line([(3, 12), (7, 8)], fill=(190, 190, 190, 255))
    save_png(top, "textures/block", "assembly_station_top.png")
    side = Image.new("RGBA", (16, 16))
    noise_fill(side, (0, 0, 16, 16), (128, 94, 58), 10, seed=22)
    ImageDraw.Draw(side).rectangle([0, 0, 15, 2], fill=(160, 122, 78, 255))
    for x0 in (2, 9):
        ImageDraw.Draw(side).rectangle([x0, 5, x0 + 4, 9], fill=(110, 80, 48, 255), outline=(70, 50, 30, 255))
        side.putpixel((x0 + 2, 7), (200, 200, 200, 255))
    ImageDraw.Draw(side).rectangle([2, 11, 13, 14], fill=(110, 80, 48, 255), outline=(70, 50, 30, 255))
    save_png(side, "textures/block", "assembly_station_side.png")
    front = side.copy()
    ImageDraw.Draw(front).ellipse([5, 3, 10, 8], fill=(40, 40, 40, 255), outline=(200, 200, 200, 255))
    ImageDraw.Draw(front).ellipse([6, 4, 9, 7], fill=(120, 170, 220, 255))
    save_png(front, "textures/block", "assembly_station_front.png")
    bottom = Image.new("RGBA", (16, 16))
    noise_fill(bottom, (0, 0, 16, 16), (100, 74, 46), 8, seed=23)
    save_png(bottom, "textures/block", "assembly_station_bottom.png")

    # charger: black plastic, slot tops, LEDs
    ch = Image.new("RGBA", (16, 16))
    noise_fill(ch, (0, 0, 16, 16), (28, 28, 30), 5, seed=24)
    save_png(ch, "textures/block", "battery_charger.png")
    chtop = ch.copy()
    for x0 in (1, 5, 9, 13):
        ImageDraw.Draw(chtop).rectangle([x0, 3, x0 + 1, 12], fill=(8, 8, 8, 255))
    save_png(chtop, "textures/block", "battery_charger_top.png")
    for state, led in (("off", (90, 20, 20, 255)), ("on", (60, 230, 90, 255))):
        f = ch.copy()
        for x0 in (2, 6, 10, 14):
            f.putpixel((x0, 8), led)
        text(f, 1, 1, "RC", (110, 110, 110, 255))
        save_png(f, "textures/block", f"battery_charger_front_{state}.png")

    # printer: white body, dark slot, output tray
    pr = Image.new("RGBA", (16, 16))
    noise_fill(pr, (0, 0, 16, 16), (226, 226, 222), 4, seed=25)
    save_png(pr, "textures/block", "photo_printer.png")
    prtop = pr.copy()
    ImageDraw.Draw(prtop).rectangle([2, 10, 13, 12], fill=(40, 40, 40, 255))
    ImageDraw.Draw(prtop).rectangle([10, 2, 13, 4], fill=(60, 120, 200, 255))
    save_png(prtop, "textures/block", "photo_printer_top.png")
    prf = pr.copy()
    ImageDraw.Draw(prf).rectangle([2, 4, 13, 6], fill=(30, 30, 30, 255))
    ImageDraw.Draw(prf).rectangle([11, 10, 13, 11], fill=(60, 200, 90, 255))
    text(prf, 1, 9, "RC", (150, 150, 150, 255))
    save_png(prf, "textures/block", "photo_printer_front.png")


def block_models():
    b = f"{NS}:block/"
    save_json({"parent": "minecraft:block/orientable_with_bottom", "textures": {
        "top": b + "assembly_station_top", "front": b + "assembly_station_front",
        "side": b + "assembly_station_side", "bottom": b + "assembly_station_bottom"}},
        "models/block", "assembly_station.json")

    def charger(state):
        return {"parent": "minecraft:block/block", "textures": {
            "particle": b + "battery_charger", "side": b + "battery_charger", "top": b + "battery_charger_top",
            "front": b + f"battery_charger_front_{state}"},
            "elements": [{"from": [2, 0, 3], "to": [14, 5, 13], "faces": {
                "north": {"uv": [2, 6, 14, 11], "texture": "#front"},
                "south": {"uv": [2, 6, 14, 11], "texture": "#side"},
                "east": {"uv": [3, 6, 13, 11], "texture": "#side"},
                "west": {"uv": [3, 6, 13, 11], "texture": "#side"},
                "up": {"uv": [2, 3, 14, 13], "texture": "#top"},
                "down": {"uv": [2, 3, 14, 13], "texture": "#side", "cullface": "down"}}}]}
    save_json(charger("off"), "models/block", "battery_charger.json")
    save_json(charger("on"), "models/block", "battery_charger_on.json")

    save_json({"parent": "minecraft:block/block", "textures": {
        "particle": b + "photo_printer", "side": b + "photo_printer", "top": b + "photo_printer_top",
        "front": b + "photo_printer_front"},
        "elements": [
            {"from": [1, 0, 2], "to": [15, 7, 14], "faces": {
                "north": {"uv": [1, 4, 15, 11], "texture": "#front"},
                "south": {"uv": [1, 9, 15, 16], "texture": "#side"},
                "east": {"uv": [2, 9, 14, 16], "texture": "#side"},
                "west": {"uv": [2, 9, 14, 16], "texture": "#side"},
                "up": {"uv": [1, 2, 15, 14], "texture": "#top"},
                "down": {"uv": [1, 2, 15, 14], "texture": "#side", "cullface": "down"}}},
            {"from": [3, 7, 11], "to": [13, 11, 13], "rotation": {"angle": -22.5, "axis": "x", "origin": [8, 7, 12]},
             "faces": {f: {"uv": [3, 3, 13, 7], "texture": "#side"} for f in ("north", "south", "east", "west", "up")}},
            {"from": [3, 1, 0], "to": [13, 2, 2], "faces": {
                f: {"uv": [3, 0, 13, 2], "texture": "#side"} for f in ("north", "east", "west", "up", "down")}}]},
        "models/block", "photo_printer.json")

    def facing_variants(model_name, extra=""):
        out = {}
        for facing, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            v = {"model": f"{NS}:block/{model_name}"}
            if y:
                v["y"] = y
            out[f"facing={facing}{extra}"] = v
        return out

    save_json({"variants": facing_variants("assembly_station")}, "blockstates", "assembly_station.json")
    save_json({"variants": facing_variants("photo_printer")}, "blockstates", "photo_printer.json")
    variants = {}
    for charging, mdl in (("false", "battery_charger"), ("true", "battery_charger_on")):
        for k, v in facing_variants(mdl).items():
            variants[f"charging={charging},{k}"] = v
    save_json({"variants": variants}, "blockstates", "battery_charger.json")
    for name in ("assembly_station", "battery_charger", "photo_printer"):
        save_json({"parent": f"{NS}:block/{name}"}, "models/item", name + ".json")


# ----------------------------------------------------------------------------- stage 4: computer, displays, album

def stage4_assets():
    b = f"{NS}:block/"
    # computer: dark case, monitor showing a photo catalogue, keyboard
    case = Image.new("RGBA", (16, 16))
    noise_fill(case, (0, 0, 16, 16), (52, 54, 58), 4, seed=41)
    save_png(case, "textures/block", "computer_case.png")
    screen = Image.new("RGBA", (16, 16))
    d = ImageDraw.Draw(screen)
    d.rectangle([0, 0, 15, 15], fill=(26, 26, 28, 255))
    d.rectangle([1, 1, 14, 10], fill=(28, 34, 51, 255))
    thumbs = [(120, 170, 220), (90, 140, 70), (220, 160, 80), (200, 90, 80), (150, 120, 190), (230, 210, 120)]
    for i, c in enumerate(thumbs):
        x0, y0 = 2 + (i % 3) * 4, 2 + (i // 3) * 4
        d.rectangle([x0, y0, x0 + 2, y0 + 2], fill=c + (255,))
    d.rectangle([2, 11, 13, 12], fill=(60, 60, 64, 255))
    text(screen, 1, 13, "RC", (120, 120, 120, 255))
    save_png(screen, "textures/block", "computer_screen.png")
    kb = Image.new("RGBA", (16, 16))
    noise_fill(kb, (0, 0, 16, 16), (40, 40, 44), 3, seed=42)
    kd = ImageDraw.Draw(kb)
    for y in range(1, 15, 3):
        for x in range(1, 15, 2):
            kd.point((x, y), fill=(150, 150, 156, 255))
    save_png(kb, "textures/block", "computer_keyboard.png")

    def box(frm, to, tex, front=None):
        faces = {}
        for f in ("north", "south", "east", "west", "up", "down"):
            faces[f] = {"texture": front if (f == "north" and front) else tex}
        return {"from": frm, "to": to, "faces": faces}

    save_json({"parent": "minecraft:block/block", "textures": {
        "particle": b + "computer_case", "case": b + "computer_case", "screen": b + "computer_screen",
        "keys": b + "computer_keyboard"},
        "elements": [
            {"from": [2, 0, 1], "to": [14, 1, 5], "faces": {
                "up": {"uv": [2, 1, 14, 5], "texture": "#keys"},
                "north": {"uv": [2, 15, 14, 16], "texture": "#case"}, "south": {"uv": [2, 15, 14, 16], "texture": "#case"},
                "east": {"uv": [1, 15, 5, 16], "texture": "#case"}, "west": {"uv": [1, 15, 5, 16], "texture": "#case"},
                "down": {"uv": [2, 1, 14, 5], "texture": "#case", "cullface": "down"}}},
            box([6, 0, 8], [10, 1, 12], "#case"),
            box([7.5, 1, 9.5], [8.5, 5, 10.5], "#case"),
            {"from": [1, 4, 8], "to": [15, 14, 10], "faces": {
                "north": {"uv": [1, 1, 15, 11], "texture": "#screen"},
                "south": {"uv": [1, 2, 15, 12], "texture": "#case"},
                "east": {"uv": [6, 2, 8, 12], "texture": "#case"}, "west": {"uv": [6, 2, 8, 12], "texture": "#case"},
                "up": {"uv": [1, 6, 15, 8], "texture": "#case"}, "down": {"uv": [1, 6, 15, 8], "texture": "#case"}}},
        ]}, "models/block", "computer.json")

    # table frame: dark walnut moulding, cream backing, a strut behind
    wood = Image.new("RGBA", (16, 16))
    noise_fill(wood, (0, 0, 16, 16), (92, 62, 40), 8, seed=43)
    for y in range(0, 16, 3):
        ImageDraw.Draw(wood).line([(0, y), (15, y)], fill=(80, 52, 32, 255))
    save_png(wood, "textures/block", "table_frame.png")
    back = Image.new("RGBA", (16, 16))
    noise_fill(back, (0, 0, 16, 16), (236, 230, 216), 3, seed=44)
    save_png(back, "textures/block", "table_frame_back.png")

    def frame_model(x0, x1, top):
        els = [
            box([x0, 0, 7], [x1, 1, 8], "#wood"),
            box([x0, top - 1, 7], [x1, top, 8], "#wood"),
            box([x0, 1, 7], [x0 + 1, top - 1, 8], "#wood"),
            box([x1 - 1, 1, 7], [x1, top - 1, 8], "#wood"),
            box([x0 + 1, 1, 7.5], [x1 - 1, top - 1, 8], "#wood", "#back"),
            {"from": [7.5, 0, 8], "to": [8.5, top - 2, 9], "rotation": {"angle": -22.5, "axis": "x",
                                                                         "origin": [8, top - 2, 8]},
             "faces": {f: {"texture": "#wood"} for f in ("north", "south", "east", "west", "up", "down")}},
        ]
        return {"parent": "minecraft:block/block", "textures": {"particle": b + "table_frame", "wood": b + "table_frame",
                                                               "back": b + "table_frame_back"},
                "elements": els,
                "display": {"gui": {"rotation": [20, 200, 0], "translation": [0, 2, 0], "scale": [0.9, 0.9, 0.9]},
                            "fixed": {"rotation": [0, 180, 0], "scale": [0.8, 0.8, 0.8]},
                            "ground": {"translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
                            "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.375, 0.375, 0.375]},
                            "firstperson_righthand": {"rotation": [0, 225, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
                            "firstperson_lefthand": {"rotation": [0, 225, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]}}}
    save_json(frame_model(3, 13, 8), "models/block", "table_frame.json")
    save_json(frame_model(4.5, 11.5, 11), "models/block", "table_frame_portrait.json")

    # photo stand (easel), light wood
    ew = Image.new("RGBA", (16, 16))
    noise_fill(ew, (0, 0, 16, 16), (190, 150, 100), 8, seed=45)
    for x in range(0, 16, 4):
        ImageDraw.Draw(ew).line([(x, 0), (x, 15)], fill=(172, 132, 86, 255))
    save_png(ew, "textures/block", "photo_stand.png")
    lower = [box([2, 0, 6], [3, 16, 7], "#t"), box([13, 0, 6], [14, 16, 7], "#t"),
             box([7.5, 0, 10], [8.5, 16, 11], "#t"), box([1, 9, 3.5], [15, 10, 7], "#t"),
             box([3, 4, 6], [13, 5, 7], "#t"), box([7.5, 4, 7], [8.5, 5, 10], "#t")]
    upper = [box([2, 0, 6], [3, 14, 7], "#t"), box([13, 0, 6], [14, 14, 7], "#t"),
             box([2, 12, 6], [14, 14, 7], "#t"), box([7.5, 0, 10], [8.5, 10, 11], "#t"),
             box([7.5, 9, 7], [8.5, 10, 10], "#t")]
    for name, els in (("photo_stand_lower", lower), ("photo_stand_upper", upper)):
        save_json({"parent": "minecraft:block/block", "textures": {"particle": b + "photo_stand", "t": b + "photo_stand"},
                   "elements": els}, "models/block", name + ".json")

    def facing(extra_key, extra_val, model_name, out):
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            v = {"model": f"{NS}:block/{model_name}"}
            if y:
                v["y"] = y
            out[f"facing={f},{extra_key}={extra_val}"] = v

    v = {}
    for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        v[f"facing={f}"] = {"model": f"{NS}:block/computer", **({"y": y} if y else {})}
    save_json({"variants": v}, "blockstates", "computer.json")
    # 16 directions like a head: turn=0 is the baked model, turned frames (22.5/45/67.5 degrees past the facing)
    # have an empty model and DisplayRenderer draws them
    save_json({"parent": "minecraft:block/block", "textures": {"particle": b + "table_frame"}, "elements": []},
              "models/block", "table_frame_turned.json")
    v = {}
    for portrait, model in (("false", "table_frame"), ("true", "table_frame_portrait")):
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            for turn in range(4):
                key = f"facing={f},portrait={portrait},turn={turn}"
                v[key] = {"model": f"{NS}:block/{model}", **({"y": y} if y else {})} if turn == 0 \
                    else {"model": f"{NS}:block/table_frame_turned"}
    save_json({"variants": v}, "blockstates", "table_frame.json")
    v = {}
    facing("half", "lower", "photo_stand_lower", v)
    facing("half", "upper", "photo_stand_upper", v)
    save_json({"variants": v}, "blockstates", "photo_stand.json")
    save_json({"parent": f"{NS}:block/computer"}, "models/item", "computer.json")
    save_json({"parent": f"{NS}:block/table_frame"}, "models/item", "table_frame.json")

    def stand_icon(img, d):
        d.line([(4, 15), (7, 1)], fill=rgba("#a8784a"), width=1)
        d.line([(11, 15), (8, 1)], fill=rgba("#a8784a"), width=1)
        d.rectangle([3, 3, 12, 10], fill=rgba("#f4f2ec"), outline=rgba("#c8c0b0"))
        d.rectangle([4, 4, 11, 9], fill=rgba("#87b5e0"))
        d.polygon([(4, 9), (7, 6), (9, 8), (11, 5), (11, 9)], fill=rgba("#4f7f3f"))
        d.line([(2, 11), (13, 11)], fill=rgba("#8a5f38"))
    icon(stand_icon, "photo_stand")

    def album_icon(img, d):
        d.rectangle([2, 1, 13, 14], fill=rgba("#6b3a22"), outline=rgba("#3e1f10"))
        d.line([(4, 1), (4, 14)], fill=rgba("#4a2614"))
        d.rectangle([6, 4, 11, 9], fill=rgba("#f2f0ea"))
        d.rectangle([7, 5, 10, 8], fill=rgba("#87b5e0"))
        d.point((8, 7), fill=rgba("#4f7f3f"))
        d.line([(6, 11), (11, 11)], fill=rgba("#d8b060"))
    icon(album_icon, "photo_album")

    def reader_icon(img, d):
        d.rectangle([2, 5, 13, 11], fill=rgba("#2a2a2e"), outline=rgba("#101012"))
        d.rectangle([4, 6, 11, 7], fill=rgba("#0a0a0a"))
        d.rectangle([5, 8, 10, 9], fill=rgba("#2a6fd6"))
        d.point((12, 10), fill=rgba("#40e060"))
        d.line([(13, 8), (15, 8)], fill=rgba("#909090"))
    icon(reader_icon, "cfexpress_reader")


# ----------------------------------------------------------------------------- stage 5: film and darkroom

def stage5_assets():
    b = f"{NS}:block/"
    # film bodies: chrome tops for the classic mechanical cameras
    save_png(camera_body_texture("NIKON", (200, 40, 30), "FM2", top_color=(186, 188, 192), body_color=(30, 30, 32)),
             "textures/item/body", "nikon_fm2.png")
    save_json(camera_body_model("nikon_fm2", "retro_slr"), "models/item/body", "nikon_fm2.json")
    save_png(camera_body_texture("LEICA", (210, 30, 30), "M6", top_color=(192, 194, 198), body_color=(26, 26, 27)),
             "textures/item/body", "leica_m6.png")
    save_json(camera_body_model("leica_m6", "rangefinder"), "models/item/body", "leica_m6.json")
    save_png(camera_body_texture("Canon", (200, 30, 30), "EOS-1V"), "textures/item/body", "canon_eos_1v.png")
    save_json(camera_body_model("canon_eos_1v", "dslr"), "models/item/body", "canon_eos_1v.json")

    def flat(folder, name, fn):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        fn(img, ImageDraw.Draw(img))
        save_png(img, f"textures/item/{folder}", name + ".png")
        save_json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{folder}/{name}"}},
                  f"models/item/{folder}", name + ".json")
    flat("battery", "sr44", battery_icon(rgba("#b8b8bc"), rgba("#404040")))
    flat("battery", "cr2", battery_icon(rgba("#2a2a2a"), rgba("#d0a020")))

    # film roll: canister, developed negative strip, ruined (fogged orange strip)
    def canister(img, d):
        d.rectangle([3, 3, 12, 13], fill=rgba("#e8b020"), outline=rgba("#6a4a08"))
        d.rectangle([3, 6, 12, 9], fill=rgba("#c81e1e"))
        d.rectangle([6, 1, 9, 3], fill=rgba("#3a3a3a"))
        d.rectangle([12, 8, 15, 11], fill=rgba("#3a2a1a"))
        text(img, 4, 10, "35", (40, 30, 10, 255))
    def strip(fill, frame):
        def f(img, d):
            d.rectangle([0, 4, 15, 12], fill=rgba(fill))
            for x in range(1, 16, 3):
                d.point((x, 5), fill=rgba("#000000", 0))
                d.point((x, 11), fill=rgba("#000000", 0))
            for x0 in (1, 6, 11):
                d.rectangle([x0, 6, x0 + 3, 10], fill=rgba(frame))
        return f
    icon(canister, "film_roll")
    icon(strip("#3a2412", "#c88a50"), "film_roll_developed")
    icon(strip("#e07020", "#ffb070"), "film_roll_ruined")
    save_json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/film_roll"},
               "overrides": [{"predicate": {f"{NS}:state": 0.3}, "model": f"{NS}:item/film_roll_developed"},
                             {"predicate": {f"{NS}:state": 0.4}, "model": f"{NS}:item/film_roll_ruined"}]},
              "models/item", "film_roll.json")

    def bottle(label):
        def f(img, d):
            d.rectangle([6, 1, 9, 3], fill=rgba("#202020"))
            d.rectangle([4, 3, 11, 14], fill=rgba("#5a3a1a"), outline=rgba("#2a1a0a"))
            d.rectangle([4, 7, 11, 11], fill=rgba(label))
            d.line([(5, 4), (5, 6)], fill=rgba("#a07040"))
        return f
    icon(bottle("#f08020"), "chemistry_c41")
    icon(bottle("#2a6fd6"), "chemistry_e6")
    icon(bottle("#d0d0d0"), "chemistry_bw")

    def bag(img, d):
        d.polygon([(2, 4), (13, 4), (14, 14), (1, 14)], fill=rgba("#141414"), outline=rgba("#000000"))
        d.rectangle([4, 2, 6, 5], fill=rgba("#2a2a2a"))
        d.rectangle([9, 2, 11, 5], fill=rgba("#2a2a2a"))
        d.line([(3, 9), (12, 9)], fill=rgba("#3a3a3a"))
    icon(bag, "changing_bag")

    def scanner(img, d):
        d.rectangle([1, 6, 14, 12], fill=rgba("#2a2a2e"), outline=rgba("#101012"))
        d.rectangle([3, 8, 12, 9], fill=rgba("#c88a50"))
        d.point((12, 11), fill=rgba("#40e060"))
    icon(scanner, "film_scanner")

    # developing tank: black plastic cylinder with a red lid
    tank = Image.new("RGBA", (16, 16))
    noise_fill(tank, (0, 0, 16, 16), (24, 24, 26), 4, seed=51)
    save_png(tank, "textures/block", "developing_tank.png")
    lid = Image.new("RGBA", (16, 16))
    noise_fill(lid, (0, 0, 16, 16), (170, 30, 30), 6, seed=52)
    ImageDraw.Draw(lid).ellipse([5, 5, 10, 10], fill=(40, 40, 40, 255))
    save_png(lid, "textures/block", "developing_tank_lid.png")
    save_json({"parent": "minecraft:block/block", "textures": {"particle": b + "developing_tank", "t": b + "developing_tank",
                                                               "lid": b + "developing_tank_lid"},
               "elements": [
                   {"from": [4.5, 0, 4.5], "to": [11.5, 8, 11.5], "faces": {
                       f: {"texture": "#t"} for f in ("north", "south", "east", "west", "down")} | {"up": {"texture": "#lid"}}},
                   {"from": [4, 7, 4], "to": [12, 9, 12], "faces": {
                       f: {"texture": "#lid"} for f in ("north", "south", "east", "west", "up", "down")}},
               ]}, "models/block", "developing_tank.json")

    # enlarger: baseboard, column, lamp head
    base = Image.new("RGBA", (16, 16))
    noise_fill(base, (0, 0, 16, 16), (230, 228, 220), 3, seed=53)
    ImageDraw.Draw(base).rectangle([3, 3, 12, 12], outline=(40, 40, 40, 255))
    save_png(base, "textures/block", "enlarger_base.png")
    metal = Image.new("RGBA", (16, 16))
    noise_fill(metal, (0, 0, 16, 16), (60, 62, 66), 6, seed=54)
    save_png(metal, "textures/block", "enlarger_metal.png")
    def bx(frm, to, tex):
        return {"from": frm, "to": to, "faces": {f: {"texture": tex} for f in ("north", "south", "east", "west", "up", "down")}}
    save_json({"parent": "minecraft:block/block", "textures": {"particle": b + "enlarger_metal", "m": b + "enlarger_metal",
                                                               "base": b + "enlarger_base"},
               "elements": [
                   {"from": [1, 0, 1], "to": [15, 1, 15], "faces": {
                       f: {"texture": "#m"} for f in ("north", "south", "east", "west", "down")} | {"up": {"texture": "#base"}}},
                   bx([7, 1, 12], [9, 16, 14], "#m"),
                   bx([5, 10, 5], [11, 14, 12], "#m"),
                   bx([6.5, 8, 6.5], [9.5, 10, 9.5], "#m"),
               ]}, "models/block", "enlarger.json")

    # safelight: dark housing with a glowing red window
    house = Image.new("RGBA", (16, 16))
    noise_fill(house, (0, 0, 16, 16), (30, 30, 30), 4, seed=55)
    save_png(house, "textures/block", "safelight.png")
    glow = Image.new("RGBA", (16, 16))
    noise_fill(glow, (0, 0, 16, 16), (220, 30, 20), 12, seed=56)
    save_png(glow, "textures/block", "safelight_glow.png")
    save_json({"parent": "minecraft:block/block", "textures": {"particle": b + "safelight", "h": b + "safelight",
                                                               "g": b + "safelight_glow"},
               "elements": [
                   bx([5, 0, 5], [11, 2, 11], "#h"),
                   {"from": [5.5, 2, 5.5], "to": [10.5, 7, 10.5], "shade": False, "faces": {
                       f: {"texture": "#g"} for f in ("north", "south", "east", "west")} | {"up": {"texture": "#h"}}},
                   bx([5, 7, 5], [11, 8, 11], "#h"),
               ]}, "models/block", "safelight.json")

    for name in ("developing_tank", "enlarger"):
        v = {}
        for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
            v[f"facing={f}"] = {"model": f"{NS}:block/{name}", **({"y": y} if y else {})}
        save_json({"variants": v}, "blockstates", name + ".json")
        save_json({"parent": f"{NS}:block/{name}"}, "models/item", name + ".json")
    save_json({"variants": {"": {"model": f"{NS}:block/safelight"}}}, "blockstates", "safelight.json")
    save_json({"parent": f"{NS}:block/safelight"}, "models/item", "safelight.json")


# ----------------------------------------------------------------------------- sounds

def stage7_assets():
    """Editorial desk (newspaper editor's job site), field journal, the editor's villager outfit."""
    b = f"{NS}:block/"
    wood = Image.new("RGBA", (16, 16))
    noise_fill(wood, (0, 0, 16, 16), (122, 86, 52), 8, seed=71)
    for y in (3, 8, 13):
        ImageDraw.Draw(wood).line([(0, y), (15, y)], fill=(100, 68, 40, 255))
    save_png(wood, "textures/block", "editorial_desk_wood.png")
    top = Image.new("RGBA", (16, 16))
    noise_fill(top, (0, 0, 16, 16), (60, 92, 64), 5, seed=72)   # green leather writing surface
    ImageDraw.Draw(top).rectangle([0, 0, 15, 15], outline=(122, 86, 52, 255))
    save_png(top, "textures/block", "editorial_desk_top.png")
    metal = Image.new("RGBA", (16, 16))
    noise_fill(metal, (0, 0, 16, 16), (36, 36, 38), 5, seed=73)
    for x in range(2, 14, 2):
        for y in (9, 11, 13):
            metal.putpixel((x, y), (200, 200, 190, 255))     # typewriter keys
    save_png(metal, "textures/block", "editorial_desk_typewriter.png")
    paper = Image.new("RGBA", (16, 16))
    noise_fill(paper, (0, 0, 16, 16), (236, 232, 220), 3, seed=74)
    for y in range(2, 15, 2):
        ImageDraw.Draw(paper).line([(2, y), (13, y)], fill=(120, 118, 112, 255))
    ImageDraw.Draw(paper).rectangle([2, 2, 7, 6], fill=(70, 70, 70, 255))  # a photo on the front page
    save_png(paper, "textures/block", "editorial_desk_paper.png")

    def bx(frm, to, tex, up=None):
        faces = {f: {"texture": tex} for f in ("north", "south", "east", "west", "up", "down")}
        if up:
            faces["up"] = {"texture": up}
        return {"from": frm, "to": to, "faces": faces}
    els = [bx([0, 12, 0], [16, 15, 16], "#w", "#t"),
           bx([1, 0, 1], [3, 12, 3], "#w"), bx([13, 0, 1], [15, 12, 3], "#w"),
           bx([1, 0, 13], [3, 12, 15], "#w"), bx([13, 0, 13], [15, 12, 15], "#w"),
           bx([3, 8, 2], [13, 12, 3], "#w"),
           bx([3.5, 15, 4], [10.5, 17.5, 10], "#k"),
           bx([4.5, 17.5, 8.5], [9.5, 21, 9], "#p"),
           bx([11.5, 15, 3], [15, 16.4, 7.5], "#p")]
    save_json({"parent": "minecraft:block/block",
               "textures": {"particle": b + "editorial_desk_wood", "w": b + "editorial_desk_wood", "t": b + "editorial_desk_top",
                            "k": b + "editorial_desk_typewriter", "p": b + "editorial_desk_paper"},
               "elements": els}, "models/block", "editorial_desk.json")
    v = {}
    for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270)):
        v[f"facing={f}"] = {"model": f"{NS}:block/editorial_desk", **({"y": y} if y else {})}
    save_json({"variants": v}, "blockstates", "editorial_desk.json")
    save_json({"parent": f"{NS}:block/editorial_desk"}, "models/item", "editorial_desk.json")

    def journal_icon(img, d):
        outline_rect(d, [3, 1, 13, 14], rgba("#6b4a2a"), rgba("#3a2814"))
        d.rectangle([3, 1, 4, 14], fill=rgba("#4a3018"))
        d.rectangle([6, 5, 11, 9], fill=rgba("#222222"))       # camera emblem
        d.ellipse([7, 6, 10, 9], fill=rgba("#9fd3ea"))
        d.rectangle([7, 4, 8, 5], fill=rgba("#222222"))
        d.line([(12, 2), (12, 13)], fill=rgba("#e8e2d0"))       # page edges
    icon(journal_icon, "field_journal")

    # villager profession overlay (64x64, villager model UV layout): green visor cap, tweed waistcoat, press card
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(t)
    cap, cap_dark = (46, 104, 70, 255), (30, 74, 48, 255)
    d.rectangle([40, 0, 47, 7], fill=cap)                       # hat layer top
    for x0 in (32, 40, 48, 56):                                  # hat layer band on all four sides
        d.rectangle([x0, 8, x0 + 7, 9], fill=cap_dark)
    d.rectangle([40, 10, 47, 10], fill=(20, 54, 34, 200))       # visor shadow on the forehead
    tweed = (112, 96, 74, 255)
    for x0, x1 in ((0, 5), (6, 13), (14, 19), (20, 27)):        # jacket layer: waistcoat down to the hips
        noise_fill(t, (x0, 44, x1 + 1, 57), tweed[:3], 10, seed=75 + x0)
    d.rectangle([9, 44, 10, 56], fill=(232, 230, 222, 255))     # shirt between the lapels
    d.rectangle([9, 45, 10, 47], fill=(150, 30, 30, 255))       # tie
    d.rectangle([11, 47, 13, 49], fill=(240, 240, 240, 255))    # press card
    d.point((12, 48), fill=(200, 30, 30, 255))
    save_png(t, "textures/entity/villager/profession", "newspaper_editor.png")


def stage6_assets():
    """Models and textures for the full catalog (tools/catalog.py)."""
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import catalog as C
    for b in C.BODIES:
        lk = b["look"]
        save_png(camera_body_texture(lk["brand"], lk["accent"], lk["text"] or "", top_color=lk["top"], body_color=lk["body"]),
                 "textures/item/body", b["key"] + ".png")
        save_json(camera_body_model(b["key"], lk["style"]), "models/item/body", b["key"] + ".json")
    for l in C.LENSES:
        make_lens(l["key"], l["label"], l["length"] / 9.0, l["diameter"] / 18.0, l["zoom"], look=l["look"], kind=l["kind"])
    for a in C.ADAPTERS:
        ring_part(a["key"], "adapter", a["spec"]["length_mm"] / 9.0, 3.4 if "hasselblad" not in a["spec"]["from"] else 3.7,
                  a["colour"])
    for t in C.TELECONVERTERS:
        ring_part(t["key"], "teleconverter", t["length_mm"] / 9.0, 3.6, t["colour"])

    def flat(folder, name, fn):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        fn(img, ImageDraw.Draw(img))
        save_png(img, f"textures/item/{folder}", name + ".png")
        save_json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{folder}/{name}"}},
                  f"models/item/{folder}", name + ".json")
    for key, (_, _, body_c, stripe) in C.BATTERIES.items():
        flat("battery", key, battery_icon(rgba(body_c), rgba(stripe)))
    flat("card", "cfexpress_b", card_icon(rgba("#2a2a2e"), rgba("#30a050")))
    flat("card", "cf", card_icon(rgba("#3a3a40"), rgba("#c0c0c8")))
    flat("card", "internal_ssd", card_icon(rgba("#505056"), rgba("#e0e0e4")))


def write_ogg(samples, rate, name):
    samples = np.clip(samples, -1, 1)
    pcm = (samples * 32000).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav_path = tmp.name
    with wave.open(wav_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(pcm.tobytes())
    out = path("sounds", name + ".ogg")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path, "-c:a", "libvorbis", "-q:a", "5", out],
                   check=True)
    os.unlink(wav_path)


def click(rate, t0, length, freq, decay, amp, rng):
    n = int(length * rate)
    t = np.arange(n) / rate
    noise = rng.normal(0, 1, n)
    tone = np.sin(2 * math.pi * freq * t) * 0.5 + np.sin(2 * math.pi * freq * 2.3 * t) * 0.3
    env = np.exp(-t / decay)
    return int(t0 * rate), amp * env * (0.65 * noise + 0.35 * tone)


def shutter_sound(rate=44100):
    """Mirrorless focal-plane shutter: first curtain click, short gap, second curtain + reset."""
    rng = np.random.default_rng(42)
    out = np.zeros(int(0.22 * rate))
    for t0, length, freq, decay, amp in ((0.0, 0.03, 3100, 0.004, 0.9), (0.012, 0.05, 1700, 0.010, 0.5),
                                         (0.075, 0.035, 2600, 0.005, 0.8), (0.09, 0.08, 900, 0.020, 0.35)):
        i, s = click(rate, t0, length, freq, decay, amp, rng)
        out[i:i + len(s)] += s[: len(out) - i]
    b, a = 0.85, 1.0  # gentle high-pass to remove rumble
    hp = np.copy(out)
    for k in range(1, len(out)):
        hp[k] = b * (hp[k - 1] + out[k] - out[k - 1])
    return hp * 0.9


def dslr_sound(rate=44100):
    """Mirror slap (low thump) + focal-plane shutter + mirror return."""
    rng = np.random.default_rng(7)
    out = np.zeros(int(0.30 * rate))
    for t0, length, freq, decay, amp in ((0.0, 0.06, 420, 0.018, 0.9), (0.006, 0.03, 2600, 0.004, 0.7),
                                         (0.11, 0.035, 2300, 0.005, 0.8), (0.13, 0.09, 380, 0.025, 0.8),
                                         (0.16, 0.05, 1400, 0.012, 0.4)):
        i, s = click(rate, t0, length, freq, decay, amp, rng)
        out[i:i + len(s)] += s[: len(out) - i]
    return out * 0.8


def leaf_sound(rate=44100):
    """Quiet, short leaf/rangefinder shutter."""
    rng = np.random.default_rng(9)
    out = np.zeros(int(0.08 * rate))
    i, s = click(rate, 0.0, 0.025, 3800, 0.003, 0.5, rng)
    out[i:i + len(s)] += s[: len(out) - i]
    i, s = click(rate, 0.012, 0.03, 2200, 0.004, 0.25, rng)
    out[i:i + len(s)] += s[: len(out) - i]
    return out


def af_beep(rate=44100):
    t = np.arange(int(0.09 * rate)) / rate
    tone = np.sin(2 * math.pi * 2650 * t)
    env = np.minimum(1, t / 0.004) * np.exp(-np.maximum(0, t - 0.03) / 0.02)
    s = tone * env * 0.35
    gap = np.zeros(int(0.035 * rate))
    return np.concatenate([s, gap, s])


def sounds():
    write_ogg(shutter_sound(), 44100, "shutter_mirrorless")
    write_ogg(af_beep(), 44100, "af_confirm")
    write_ogg(dslr_sound(), 44100, "shutter_dslr")
    write_ogg(leaf_sound(), 44100, "shutter_leaf")
    save_json({
        "shutter.mirrorless": {"subtitle": "subtitles.realcamera.shutter", "sounds": [
            {"name": f"{NS}:shutter_mirrorless", "attenuation_distance": 16}]},
        "shutter.dslr": {"subtitle": "subtitles.realcamera.shutter", "sounds": [
            {"name": f"{NS}:shutter_dslr", "attenuation_distance": 20}]},
        "shutter.leaf": {"subtitle": "subtitles.realcamera.shutter", "sounds": [
            {"name": f"{NS}:shutter_leaf", "attenuation_distance": 8}]},
        "af_confirm": {"subtitle": "subtitles.realcamera.af_confirm", "sounds": [{"name": f"{NS}:af_confirm"}]},
    }, "sounds.json")


# ----------------------------------------------------------------------------- photo print model

def print_model():
    save_png(Image.new("RGBA", (16, 16), (245, 243, 238, 255)), "textures/item", "photo_print.png")
    save_json({
        "parent": "minecraft:builtin/entity",
        "gui_light": "front",
        "textures": {"particle": f"{NS}:item/photo_print"},
        "display": {
            "gui": {"rotation": [0, 0, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "ground": {"rotation": [0, 0, 0], "translation": [0, 3, 0], "scale": [0.5, 0.5, 0.5]},
            "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [1, 1, 1]},
            "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
            "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 3, 1], "scale": [0.55, 0.55, 0.55]},
            "firstperson_righthand": {"rotation": [0, -15, -5], "translation": [1, 3, 0], "scale": [0.5, 0.5, 0.5]},
            "firstperson_lefthand": {"rotation": [0, 15, 5], "translation": [1, 3, 0], "scale": [0.5, 0.5, 0.5]},
        }}, "models/item", "photo_print.json")


# ----------------------------------------------------------------------------- main

def main():
    materials = {
        "optical_glass": draw_optical_glass,
        "lens_element": lens_disc(rgba("#9fd3ea", 230), rgba("#4f8fb0")),
        "ed_lens_element": lens_disc(rgba("#b9e6b0", 230), rgba("#c9a54a")),
        "silicon_wafer": draw_wafer,
        "cmos_sensor": sensor(rgba("#2d3a66")),
        "bsi_cmos_sensor": sensor(rgba("#5b2d7a")),
        "stacked_cmos_sensor": sensor(rgba("#1f6b5a")),
        "medium_format_sensor": sensor(rgba("#7a5a1f")),
        "titanium_alloy": draw_ingot(rgba("#b4aa94")),
        "processor": draw_processor,
        "circuit_board": draw_board,
        "display_panel": draw_display,
        "shutter_unit": draw_shutter,
        "magnesium_alloy": draw_ingot(rgba("#c5ccd4")),
        "plastic": draw_plastic,
        "aperture_blades": draw_blades,
        "af_motor": draw_motor,
        "stabilizer": draw_stabilizer,
        "battery_cell": draw_cell,
        "flash_memory": draw_flash,
        "ink_cartridge_cyan": cartridge(rgba("#00b7eb")),
        "ink_cartridge_magenta": cartridge(rgba("#ff00a0")),
        "ink_cartridge_yellow": cartridge(rgba("#ffe600")),
        "ink_cartridge_black": cartridge(rgba("#303030")),
        "photo_paper": draw_paper,
        "photo_frame": draw_frame_icon,
        "camera_body": draw_camera_icon,
        "lens": draw_lens_icon,
        "battery": battery_icon(rgba("#2a2a2a"), rgba("#7a7a7a")),
        "memory_card": card_icon(rgba("#1f2f5a"), rgba("#d0d0d0")),
    }
    for name, fn in materials.items():
        icon(fn, name)

    # catalog: battery and card models are flat icons in their own folders
    def flat(folder, name, fn):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        fn(img, ImageDraw.Draw(img))
        save_png(img, f"textures/item/{folder}", name + ".png")
        save_json({"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{folder}/{name}"}},
                  f"models/item/{folder}", name + ".json")

    flat("battery", "np_fz100", battery_icon(rgba("#2b2b2d"), rgba("#c8c8c8")))
    flat("card", "sd_uhs1", card_icon(rgba("#2b2b2b"), rgba("#d22b2b")))
    flat("card", "sd_uhs2", card_icon(rgba("#1a1a1a"), rgba("#e6b422")))
    flat("card", "cfexpress_a", card_icon(rgba("#3a3a3a"), rgba("#2a6fd6")))

    # 3D catalog models
    save_png(camera_body_texture("SONY", (210, 60, 40)), "textures/item/body", "sony_a7iv.png")
    save_json(camera_body_model("sony_a7iv"), "models/item/body", "sony_a7iv.json")
    make_lens("sony_fe_28_70_oss", "28-70", length=9.2, radius=3.9, zoom=True)
    make_lens("sony_fe_85_f18", "85 1.8", length=9.1, radius=4.2, zoom=False)
    make_lens("sony_e_pz_16_50", "16-50", length=3.4, radius=3.4, zoom=True)
    make_lens("sony_fe_200_600_g", "200-600", length=22, radius=6.0, zoom=True)
    make_lens("nikon_afs_50_f18g", "50 1.8", length=5.9, radius=4.0, zoom=False)
    make_lens("canon_ef_50_f18_stm", "50 1.8", length=4.4, radius=3.8, zoom=False)
    make_lens("leica_summicron_m_50_f2", "50 2", length=4.9, radius=3.0, zoom=False)
    save_png(camera_body_texture("NIKON", (200, 40, 30), "D750"), "textures/item/body", "nikon_d750.png")
    save_json(camera_body_model("nikon_d750", "dslr"), "models/item/body", "nikon_d750.json")
    save_png(camera_body_texture("LEICA", (210, 30, 30), "M11", top_color=(180, 182, 186)), "textures/item/body", "leica_m11.png")
    save_json(camera_body_model("leica_m11", "rangefinder"), "models/item/body", "leica_m11.json")
    ring_part("sigma_mc11_ef_e", "adapter", 26.0 / 9, 3.4, (30, 30, 32))
    ring_part("f_to_e", "adapter", 28.5 / 9, 3.4, (60, 60, 64))
    ring_part("m_to_e", "adapter", 9.8 / 9, 3.0, (150, 152, 156))
    ring_part("sony_sel14tc", "teleconverter", 20.0 / 9, 3.6, (225, 225, 222))
    ring_part("sony_sel20tc", "teleconverter", 30.0 / 9, 3.6, (225, 225, 222))
    flat("battery", "en_el15c", battery_icon(rgba("#26262a"), rgba("#e0c040")))
    flat("battery", "bp_scl7", battery_icon(rgba("#3a3a3c"), rgba("#d02020")))
    icon(draw_lens_icon, "mount_adapter")
    icon(draw_lens_icon, "teleconverter")
    # stage 3 accessories
    flash_part("sony_hvl_f46rm", (28, 28, 30), "SONY")
    flash_part("nikon_sb700", (24, 24, 26), "NIKON")
    flash_part("canon_el5", (30, 30, 32), "CANON")
    flash_part("fuji_ef60", (34, 34, 36), "FUJI")
    flash_part("godox_v1", (22, 22, 24), "GODOX")
    grip_part("sony_vg_c4em", (32, 32, 34))
    grip_part("nikon_mb_d16", (30, 30, 32))

    def filter_icon(tint, alpha=110):
        def f(img, d):
            d.ellipse([1, 1, 14, 14], fill=rgba("#1c1c1c"))
            d.ellipse([3, 3, 12, 12], fill=tint[:3] + (alpha,))
            d.line([(5, 5), (7, 4)], fill=rgba("#ffffff", 200))
        return f
    import sys as _sys
    _sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import catalog as _C
    for fl in _C.FILTERS:
        flat("filter", fl["key"], filter_icon(rgba(fl["tint"]), fl["alpha"]))
    icon(draw_flash_icon, "flash")
    icon(filter_generic, "filter")
    icon(draw_grip_icon, "battery_grip")
    icon(draw_tripod_icon, "tripod")
    icon(draw_bag_icon, "camera_bag")
    icon(draw_kit_icon, "cleaning_kit")

    block_textures()
    block_models()
    stage4_assets()
    stage5_assets()
    stage6_assets()
    stage7_assets()
    print_model()
    sounds()
    print("assets written to", os.path.relpath(ASSETS, ROOT))


if __name__ == "__main__":
    main()
