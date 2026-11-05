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
    """Octagonal prism along z: an axis-aligned box plus the same box rotated 45° around z."""
    s = r * 0.8284  # half-width of the square so that the union is a regular octagon of radius r
    faces = {}
    for f in ("east", "west", "up", "down"):
        faces[f] = {"uv": face_uv(side_region), "texture": tex}
    if cap_front:
        faces["north"] = {"uv": face_uv(cap_front), "texture": tex}
    if cap_back:
        faces["south"] = {"uv": face_uv(cap_back), "texture": tex}
    a = element([cx - s, cy - r, z0], [cx + s, cy + r, z1], dict(faces))
    b = element([cx - r, cy - s, z0], [cx + r, cy + s, z1], dict(faces))
    faces_rot = dict(faces)
    c = element([cx - s, cy - r, z0], [cx + s, cy + r, z1], faces_rot,
                rotation={"angle": 45, "axis": "z", "origin": [cx, cy, (z0 + z1) / 2]})
    return [a, b, c]


ITEM_DISPLAY_3D = {
    "gui": {"rotation": [20, 150, 0], "translation": [0, -0.5, 0], "scale": [0.78, 0.78, 0.78]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.7, 0.7, 0.7]},
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
    save_json(model(tex, build(8, 8, 8 + length_units / 2), LENS_DISPLAY), f"models/item/{folder}", name + ".json")
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


def lens_texture(label, ring_color=None, gm=False):
    t = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    noise_fill(t, (0, 0, 32, 16), (26, 26, 27), 5, seed=11)        # barrel
    noise_fill(t, (32, 0, 64, 16), (26, 26, 27), 5, seed=12)       # barrel with label
    # brand/focal printing as plain stripes: pixel text would appear mirrored on some faces
    ImageDraw.Draw(t).line([(34, 6), (34 + 3 * len(label), 6)], fill=(200, 200, 200, 255))
    if gm:
        ImageDraw.Draw(t).rectangle([33, 11, 41, 13], fill=(232, 118, 30, 255))
    if ring_color:
        ImageDraw.Draw(t).line([(32, 15), (63, 15)], fill=ring_color)
    # zoom ring: coarse ribs
    for x in range(0, 32):
        c = 44 if x % 4 < 2 else 18
        ImageDraw.Draw(t).line([(x, 16), (x, 31)], fill=(c, c, c + 1, 255))
    # focus ring: fine ribs
    for x in range(32, 64):
        c = 40 if x % 2 == 0 else 22
        ImageDraw.Draw(t).line([(x, 16), (x, 31)], fill=(c, c, c, 255))
    # front glass: dark coated glass with reflections
    for y in range(32, 64):
        for x in range(0, 32):
            dx, dy = (x - 15.5) / 16, (y - 47.5) / 16
            r = math.hypot(dx, dy)
            base = np.array([18, 22, 48]) * (1.2 - r * 0.6)
            if 0.55 < r < 0.62:
                base = np.array([110, 70, 150])
            if (dx + 0.25) ** 2 + (dy + 0.3) ** 2 < 0.02:
                base = np.array([230, 235, 255])
            t.putpixel((x, y), tuple(int(max(0, min(255, v))) for v in base) + (255,))
    # mount (metal) and rear cap black
    noise_fill(t, (32, 32, 64, 48), (178, 180, 184), 14, seed=13)
    noise_fill(t, (32, 48, 64, 64), (14, 14, 15), 4, seed=14)
    return t


def lens_elements(cx, cy, z_back, length, radius, R, zoom=True):
    """Lens built from the mount (z_back) forward (towards -z)."""
    els = []
    z_front = z_back - length
    # mount flange
    els += cylinder(cx, cy, z_back - 0.6, z_back, radius * 0.72, R["metal"], cap_back=R["rear"])
    # rear barrel
    seg = length - 0.6
    zoom_len = seg * 0.32 if zoom else 0
    focus_len = seg * (0.22 if zoom else 0.42)
    barrel_len = seg - zoom_len - focus_len
    z = z_back - 0.6
    els += cylinder(cx, cy, z - barrel_len * 0.55, z, radius * 0.92, R["label"])
    z -= barrel_len * 0.55
    if zoom:
        els += cylinder(cx, cy, z - zoom_len, z, radius, R["zoom"])
        z -= zoom_len
    els += cylinder(cx, cy, z - barrel_len * 0.45, z, radius * 0.95, R["barrel"])
    z -= barrel_len * 0.45
    els += cylinder(cx, cy, z - focus_len, z, radius, R["focus"], cap_front=R["glass"])
    return els


LENS_R = {"barrel": (0, 0, 32, 16), "label": (32, 0, 64, 16), "zoom": (0, 16, 32, 32), "focus": (32, 16, 64, 32),
          "glass": (0, 32, 32, 64), "metal": (32, 32, 64, 48), "rear": (32, 48, 64, 64)}

LENS_DISPLAY = {
    "gui": {"rotation": [25, -40, 0], "translation": [0, 0, 0], "scale": [0.75, 0.75, 0.75]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
    "fixed": {"rotation": [0, 180, 0], "translation": [0, 0, 0], "scale": [0.75, 0.75, 0.75]},
    "thirdperson_righthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
    "thirdperson_lefthand": {"rotation": [75, 0, 0], "translation": [0, 2.5, 0], "scale": [0.45, 0.45, 0.45]},
    "firstperson_righthand": {"rotation": [0, -30, 0], "translation": [1, 2, 0], "scale": [0.55, 0.55, 0.55]},
    "firstperson_lefthand": {"rotation": [0, 30, 0], "translation": [1, 2, 0], "scale": [0.55, 0.55, 0.55]},
}


def make_lens(name, label, length, radius, zoom, gm=False, ring=None):
    save_png(lens_texture(label, ring, gm), "textures/item/lens", name + ".png")
    tex = f"{NS}:item/lens/{name}"
    # standalone: centred in the item box
    standalone = lens_elements(8, 8, 8 + length / 2, length, radius, LENS_R, zoom)
    save_json(model(tex, standalone, LENS_DISPLAY), "models/item/lens", name + ".json")
    # mounted: rear flange on the body's mount face; the body's display transforms apply
    mounted = lens_elements(MOUNT_X, MOUNT_Y, MOUNT_Z, length, radius, LENS_R, zoom)
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


# ----------------------------------------------------------------------------- sounds

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
    for name, tint, alpha in (("nd8_82", rgba("#555555"), 120), ("nd64_82", rgba("#333333"), 170),
                              ("nd1000_82", rgba("#111111"), 230), ("cpl_82", rgba("#3a4a66"), 140),
                              ("uv_82", rgba("#ccd8ff"), 60), ("yellow_82", rgba("#ffd800"), 150),
                              ("orange_82", rgba("#ff8a00"), 160), ("red_82", rgba("#d01818"), 170),
                              ("cpl_95", rgba("#3a4a66"), 140)):
        flat("filter", name, filter_icon(tint, alpha))
    icon(draw_flash_icon, "flash")
    icon(filter_generic, "filter")
    icon(draw_grip_icon, "battery_grip")
    icon(draw_tripod_icon, "tripod")
    icon(draw_bag_icon, "camera_bag")
    icon(draw_kit_icon, "cleaning_kit")

    block_textures()
    block_models()
    print_model()
    sounds()
    print("assets written to", os.path.relpath(ASSETS, ROOT))


if __name__ == "__main__":
    main()
