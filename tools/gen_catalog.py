#!/usr/bin/env python3
"""
Writes the stage 6 catalog (tools/catalog.py) as datapack registry JSON and merges its names into the en_us /
ru_ru lang files. Run from the project root: python3 tools/gen_catalog.py
Only files of entries listed in catalog.py are (over)written; hand-written entries of earlier stages stay.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import catalog as C  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
DATA = os.path.join(ROOT, "src/main/resources/data/realcamera/realcamera")
LANG = os.path.join(ROOT, "src/main/resources/assets/realcamera/lang")


def write(registry, key, obj):
    d = os.path.join(DATA, registry)
    os.makedirs(d, exist_ok=True)
    with open(os.path.join(d, key + ".json"), "w", encoding="utf-8") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def ru(name):
    """Russian display name: brand and model names stay, generic words are translated."""
    return (name.replace("Adapter ", "Адаптер ").replace("Teleconverter", "телеконвертер")
            .replace("Extender", "экстендер").replace(" Battery", ", аккумулятор"))


def main():
    names = {}
    for key, (name, fl, throat) in C.MOUNTS.items():
        write("mount", key, {"name": name, "flange_mm": fl, "throat_mm": throat})
        names[f"realcamera.mount.realcamera.{key}"] = name
    for key, name in C.APSC_MOUNT_NAMES.items():
        names[f"realcamera.mount.realcamera.{key}.apsc"] = name
    for key, (name, cap, _, _) in C.BATTERIES.items():
        write("battery_type", key, {"name": name, "capacity": cap, "model": C.rc(f"item/battery/{key}")})
        names[f"realcamera.battery_type.realcamera.{key}"] = name
    for key, (name, form, gb, speed, icon) in C.CARDS.items():
        write("memory_card_type", key, {"name": name, "form": form, "capacity_gb": gb, "write_mb_s": speed,
                                        "model": C.rc(f"item/card/{icon}")})
        names[f"realcamera.memory_card_type.realcamera.{key}"] = name
    for key, (name, contrast, sat, mono, tr, tg, tb, lift) in C.NEW_PROFILES.items():
        obj = {"name": name, "contrast": contrast, "saturation": sat}
        if mono:
            obj["monochrome"] = True
        for k, v in (("tint_r", tr), ("tint_g", tg), ("tint_b", tb)):
            if v != 1.0:
                obj[k] = v
        if lift:
            obj["shadow_lift"] = lift
        write("color_profile", key, obj)
    for b in C.BODIES:
        write("camera_body", b["key"], b["spec"])
        names[f"realcamera.camera_body.realcamera.{b['key']}"] = b["name"]
    for l in C.LENSES:
        write("lens", l["key"], l["spec"])
        names[f"realcamera.lens.realcamera.{l['key']}"] = l["name"]
    for a in C.ADAPTERS:
        write("mount_adapter", a["key"], a["spec"])
        names[f"realcamera.mount_adapter.realcamera.{a['key']}"] = a["name"]
    for t in C.TELECONVERTERS:
        write("teleconverter", t["key"], t["spec"])
        names[f"realcamera.teleconverter.realcamera.{t['key']}"] = t["name"]

    names_ru = {}
    for fl in C.FILTERS:
        write("filter_type", fl["key"], fl["spec"])
        names[f"realcamera.filter_type.realcamera.{fl['key']}"] = fl["name"]
        names_ru[f"realcamera.filter_type.realcamera.{fl['key']}"] = fl["name_ru"]
    for ring in C.STEP_UP_RINGS:
        write("step_up_ring", ring["key"], ring["spec"])
        names[f"realcamera.step_up_ring.realcamera.{ring['key']}"] = ring["name"]
        names_ru[f"realcamera.step_up_ring.realcamera.{ring['key']}"] = ring["name_ru"]

    for lang, conv in (("en_us", lambda s: s), ("ru_ru", ru)):
        path = os.path.join(LANG, lang + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        for k, v in names.items():
            data[k] = names_ru[k] if lang == "ru_ru" and k in names_ru else conv(v)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print(f"catalog: {len(C.BODIES)} bodies, {len(C.LENSES)} lenses, {len(C.ADAPTERS)} adapters, "
          f"{len(C.TELECONVERTERS)} teleconverters, {len(C.MOUNTS)} mounts, {len(C.BATTERIES)} batteries")


if __name__ == "__main__":
    main()
