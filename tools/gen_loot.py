#!/usr/bin/env python3
"""
Structure loot (plan §8.3) as NeoForge global loot modifiers of type realcamera:add_items.
Writes data/realcamera/loot_modifiers/*.json and data/neoforge/loot_modifiers/global_loot_modifiers.json.
Run from the project root: python3 tools/gen_loot.py
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "src/main/resources/data/realcamera/loot_modifiers")
GLOBAL = os.path.join(ROOT, "src/main/resources/data/neoforge/loot_modifiers/global_loot_modifiers.json")
NS = "realcamera"


def rc(n):
    return f"{NS}:{n}"


def stack(item, components=None, count=1):
    s = {"id": rc(item), "count": count}
    if components:
        s["components"] = components
    return s


def body(key, wear=None):
    c = {rc("body"): rc(key)}
    if wear:
        c[rc("wear")] = wear
    return stack("camera_body", c)


def lens(key):
    return stack("lens", {rc("lens"): rc(key)})


def adapter(key):
    return stack("mount_adapter", {rc("adapter"): rc(key)})


def film(stock, fmt="135", count=1):
    return stack("film_roll", {rc("film_stock"): rc(stock), rc("film"): {"format": fmt, "state": 0}}, count)


def filt(key):
    return stack("filter", {rc("filter_type"): rc(key)})


BROKEN = {"shutter_jammed": True, "moisture": 100.0, "dead": True}

MODIFIERS = {
    # village cartographer: a Soviet film kit
    "village_cartographer": (["minecraft:chests/village/village_cartographer"], 2, [
        (film("kodak_tri_x_400", count=2), 0.35), (film("kodak_portra_400"), 0.25), (body("zenit_e"), 0.12),
        (lens("helios_44_2"), 0.15), (lens("jupiter_9"), 0.06), (stack("chemistry_bw"), 0.2), (adapter("m42_to_e"), 0.1),
    ]),
    # ordinary village houses: now and then a roll of film
    "village_houses": ([f"minecraft:chests/village/village_{v}_house" for v in ("plains", "taiga", "snowy", "savanna", "desert")], 1, [
        (film("kodak_gold_200"), 0.08), (film("ilford_hp5_plus_400"), 0.05),
    ]),
    # shipwrecks: drowned cameras (for parts) and filters
    "shipwreck": (["minecraft:chests/shipwreck_supply", "minecraft:chests/shipwreck_treasure", "minecraft:chests/shipwreck_map"], 2, [
        (body("nikon_d3500", BROKEN), 0.12), (body("canon_eos_250d", BROKEN), 0.12), (body("sony_a6400", BROKEN), 0.08),
        (filt("uv_82"), 0.25), (stack("cleaning_kit"), 0.15),
    ]),
    # treasure and strongholds: vintage classics
    "vintage_treasure": (["minecraft:chests/buried_treasure", "minecraft:chests/stronghold_library",
                          "minecraft:chests/woodland_mansion"], 1, [
        (body("leica_m6"), 0.06), (body("hasselblad_500cm"), 0.05), (lens("leica_summicron_m_50_f2"), 0.08),
        (lens("zeiss_planar_80_f28"), 0.06), (film("kodak_portra_400", "120", 2), 0.15), (lens("meyer_trioplan_100"), 0.05),
    ]),
    # ancient cities and trial chambers: rare flagship parts
    "rare_parts": (["minecraft:chests/ancient_city", "minecraft:chests/trial_chambers/reward_rare",
                    "minecraft:chests/trial_chambers/reward_unique", "minecraft:chests/trial_chambers/reward_ominous_rare"], 1, [
        (stack("stacked_cmos_sensor"), 0.12), (stack("medium_format_sensor"), 0.05), (stack("titanium_alloy", count=2), 0.12),
        (body("contax_t2"), 0.05),
    ]),
}


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    names = []
    for name, (tables, max_items, entries) in MODIFIERS.items():
        conditions = [{"condition": "neoforge:loot_table_id", "loot_table_id": t} for t in tables]
        obj = {
            "type": rc("add_items"),
            "conditions": [{"condition": "minecraft:any_of", "terms": conditions}] if len(conditions) > 1 else conditions,
            "entries": [{"item": s, "chance": c} for s, c in entries],
            "max_items": max_items,
        }
        with open(os.path.join(OUT, name + ".json"), "w") as f:
            json.dump(obj, f, indent=2, ensure_ascii=False)
            f.write("\n")
        names.append(rc(name))
    os.makedirs(os.path.dirname(GLOBAL), exist_ok=True)
    with open(GLOBAL, "w") as f:
        json.dump({"replace": False, "entries": names}, f, indent=2)
        f.write("\n")
    print(len(names), "loot modifiers written")


if __name__ == "__main__":
    main()
