#!/usr/bin/env python3
"""
Generates RealCamera recipes into src/main/resources/data/realcamera/recipe/ and block loot tables into
src/main/resources/data/realcamera/loot_table/blocks/.
Run from the project root: python3 tools/gen_recipes.py   (old generated files are removed first)

Assembly recipes ("realcamera:assembly") run in the Photo Workshop: shapeless, with counts,
the result may carry data components, "time" is in ticks.
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "src/main/resources/data/realcamera/recipe")
LOOT = os.path.join(ROOT, "src/main/resources/data/realcamera/loot_table/blocks")
NS = "realcamera"


def mc(name):
    return "minecraft:" + name


def rc(name):
    return f"{NS}:" + name


def save(name, obj):
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


# Items with a maximum stack size of 1 (RCItems .stacksTo(1)); a recipe cannot make more than one.
STACKS_TO_ONE = {"realcamera:film_roll", "realcamera:camera_body", "realcamera:lens"}

# The photo workshop has 6 input slots (AssemblyRecipe allows 1..6 ingredients).
MAX_INGREDIENTS = 6
# When a recipe needs more kinds of parts than that, cheap extras are folded into a related part, in this order:
# (item folded away, item that takes its count). Weather sealing and plain iron go into the housing (the first
# ingredient), a leaf shutter carries its own blades, AF motors sit on the circuit board, screens on the processor.
FOLDS = [("minecraft:slime_ball", None), ("minecraft:iron_ingot", None),
         ("realcamera:aperture_blades", "realcamera:shutter_unit"),
         ("realcamera:af_motor", "realcamera:circuit_board"),
         ("realcamera:display_panel", "realcamera:processor")]


def fit(name, ingredients):
    """Merge repeated items, then fold extras until the recipe fits the workshop."""
    merged = {}
    for item, n in ingredients:
        merged[item] = merged.get(item, 0) + n
    for item, into in FOLDS:
        if len(merged) <= MAX_INGREDIENTS:
            break
        if item not in merged:
            continue
        target = into if into is not None else next(iter(merged))
        if target == item or target not in merged:
            continue
        merged[target] += merged.pop(item)
    if len(merged) > MAX_INGREDIENTS:
        raise SystemExit(f"recipe {name}: {len(merged)} ingredients, the workshop has {MAX_INGREDIENTS} slots: {merged}")
    return list(merged.items())


def assembly(name, ingredients, result, count=1, time=100, components=None):
    ingredients = fit(name, ingredients)
    if result in STACKS_TO_ONE and count != 1:
        raise SystemExit(f"recipe {name}: {result} does not stack, the result count must be 1 (was {count})")
    res = {"id": result, "count": count}
    if components:
        res["components"] = components
    save(name, {
        "type": f"{NS}:assembly",
        "ingredients": [{"item": item, "count": n} for item, n in ingredients],
        "result": res,
        "time": time,
    })


def stage6_catalog():
    """Recipes for everything in tools/catalog.py, from tier and kind."""
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    import catalog as C

    assembly("stacked_cmos_sensor", [(rc("bsi_cmos_sensor"), 1), (rc("processor"), 1), (mc("diamond"), 1)],
             rc("stacked_cmos_sensor"), 1, 160)
    assembly("medium_format_sensor", [(rc("bsi_cmos_sensor"), 2), (mc("netherite_scrap"), 1), (mc("gold_ingot"), 2)],
             rc("medium_format_sensor"), 1, 200)
    assembly("titanium_alloy", [(mc("iron_ingot"), 2), (mc("netherite_scrap"), 1)], rc("titanium_alloy"), 4, 120)

    housing = {"entry": (rc("plastic"), 3), "mid": (rc("magnesium_alloy"), 3), "pro": (rc("magnesium_alloy"), 4),
               "flagship": (rc("titanium_alloy"), 2)}
    for b in C.BODIES:
        spec, tier = b["spec"], b["tier"]
        ing = [housing[tier]]
        if "film" in spec:
            ing += [(rc("shutter_unit"), 1), (rc("aperture_blades"), 1), (mc("iron_ingot"), 2)]
            # classic film cameras are precision mechanics: the better ones cost real gold and diamonds
            ing += {"entry": [], "mid": [(mc("gold_ingot"), 2)], "pro": [(mc("diamond"), 1)],
                    "flagship": [(mc("gold_ingot"), 3), (mc("diamond"), 2)]}[tier]
            if not spec["film"]["mechanical"]:
                ing.append((rc("circuit_board"), 1))
            if spec["af"]["speed"] > 0:
                ing.append((rc("af_motor"), 1))
        else:
            sensor = ("medium_format_sensor" if spec["sensor"]["width_mm"] > 40 else
                      "stacked_cmos_sensor" if spec["shutter"]["readout_ms"] <= 10 else
                      "cmos_sensor" if tier == "entry" else "bsi_cmos_sensor")
            ing += [(rc(sensor), 1), (rc("processor"), 1 if tier == "entry" else 2), (rc("display_panel"), 1 if spec["viewfinder"] == "none" else 2)]
            if spec["shutter"]["mech_min"] > 0:
                ing.append((rc("shutter_unit"), 1))
            if spec["ibis_stops"] > 0:
                ing.append((rc("stabilizer"), 1))
        if "fixed_lens" in spec:
            ing += [(rc("lens_element"), 4), (rc("aperture_blades"), 1)]
        if spec["weather_sealing"] >= 2:
            ing.append((mc("slime_ball"), 2))
        time = {"entry": 300, "mid": 400, "pro": 500, "flagship": 600}[tier]
        assembly(f"body_{b['key']}", ing, rc("camera_body"), 1, time, {f"{NS}:body": rc(b["key"])})

    for l in C.LENSES:
        if l["fixed"]:
            continue
        spec, tier = l["spec"], l["tier"]
        elements = 3 + (2 if l["zoom"] else 0) + (2 if spec["aperture_wide"] <= 1.8 else 0) + (2 if spec["focal_max"] >= 300 else 0)
        ing = [(rc("lens_element"), elements), (rc("aperture_blades"), 1)]
        if tier == "pro":
            ing.append((rc("ed_lens_element"), 2 if spec["focal_max"] < 300 else 4))
        elif tier == "flagship":
            # hand-made glass: many exotic elements and a precious barrel instead of motors
            ing += [(rc("ed_lens_element"), 4), (mc("gold_ingot"), 2)]
        if spec["af"]["motor"] != "none":
            ing.append((rc("af_motor"), 1))
        if spec["ois_stops"] > 0:
            ing.append((rc("stabilizer"), 1))
        ing.append((rc("plastic"), 2) if tier == "entry" else (rc("magnesium_alloy"), 2 if spec["focal_max"] < 300 else 4))
        if l["kind"] == "vintage":
            ing.append((mc("iron_ingot"), 1))
        if "tilt_shift" in spec["optics"]:
            ing.append((mc("piston"), 2))
        time = {"entry": 200, "mid": 260, "pro": 340, "flagship": 400}[tier]
        assembly(f"lens_{l['key']}", ing, rc("lens"), 1, time, {f"{NS}:lens": rc(l["key"])})

    for a in C.ADAPTERS:
        spec = a["spec"]
        ing = [(rc("magnesium_alloy"), 1), (rc("circuit_board"), 1)] if spec["electronic"] else [(mc("iron_ingot"), 1), (mc("iron_nugget"), 3)]
        if spec["af"] != "none":
            ing.append((rc("processor"), 1))
        assembly(f"adapter_{a['key']}", ing, rc("mount_adapter"), 1, 120 if spec["electronic"] else 60,
                 {f"{NS}:adapter": rc(a["key"])})
    for t in C.TELECONVERTERS:
        ing = [(rc("lens_element"), 3 if t["spec"]["factor"] < 1.6 else 4), (rc("magnesium_alloy"), 1), (rc("circuit_board"), 1)]
        assembly(f"tc_{t['key']}", ing, rc("teleconverter"), 1, 160, {f"{NS}:teleconverter": rc(t["key"])})
    for key, (name, cap, _, _) in C.BATTERIES.items():
        small = cap >= 1000 and key in ("px28", "px625", "cr2_lithium", "sr44_single")
        ing = [(rc("battery_cell"), 1), (mc("iron_nugget"), 1)] if small else \
              [(rc("battery_cell"), 2), (rc("plastic"), 1), (rc("circuit_board"), 1)]
        charge = cap if small else cap // 2
        assembly(f"battery_{key}", ing, rc("battery"), 1, 80,
                 {f"{NS}:battery_type": rc(key), f"{NS}:charge": {"charge": charge, "capacity": cap}})
    card = {"used_bytes": 0, "photo_count": 0}
    for key, (name, form, gb, speed, icon) in C.CARDS.items():
        if form.startswith("internal"):
            continue  # built into a body
        ing = [(rc("flash_memory"), max(2, gb // 64)), (rc("plastic"), 1), (rc("circuit_board"), 1)]
        if form == "cfexpress_b":
            ing += [(rc("processor"), 1), (rc("magnesium_alloy"), 1)]
        assembly(f"card_{key}", ing, rc("memory_card"), 1, 120,
                 {f"{NS}:card_type": rc(key), f"{NS}:card": card})


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)

    # --- the workshop itself (vanilla crafting table)
    save("assembly_station", {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "pattern": ["IGI", "PCP", "PPP"],
        "key": {"I": {"item": mc("iron_ingot")}, "G": {"item": mc("glass_pane")},
                "C": {"item": mc("crafting_table")}, "P": {"tag": "minecraft:planks"}},
        "result": {"id": rc("assembly_station"), "count": 1},
    })

    # --- intermediate parts from vanilla materials (plan §8.1)
    assembly("optical_glass", [(mc("glass"), 2), (mc("quartz"), 1)], rc("optical_glass"), 2, 60)
    assembly("lens_element", [(rc("optical_glass"), 1), (mc("amethyst_shard"), 1)], rc("lens_element"), 1, 60)
    assembly("ed_lens_element", [(rc("lens_element"), 2), (mc("diamond"), 1)], rc("ed_lens_element"), 2, 120)
    assembly("silicon_wafer", [(mc("quartz"), 2), (mc("redstone"), 1)], rc("silicon_wafer"), 2, 80)
    assembly("cmos_sensor", [(rc("silicon_wafer"), 1), (mc("gold_ingot"), 1), (mc("redstone"), 2)], rc("cmos_sensor"), 1, 100)
    assembly("bsi_cmos_sensor", [(rc("cmos_sensor"), 1), (mc("amethyst_shard"), 2), (mc("gold_ingot"), 1)],
             rc("bsi_cmos_sensor"), 1, 120)
    assembly("processor", [(rc("silicon_wafer"), 1), (mc("gold_ingot"), 1), (mc("copper_ingot"), 1), (mc("redstone"), 2)],
             rc("processor"), 1, 100)
    assembly("circuit_board", [(mc("copper_ingot"), 2), (mc("green_dye"), 1), (mc("redstone"), 1)], rc("circuit_board"), 2, 60)
    assembly("display_panel", [(mc("glass_pane"), 1), (mc("glowstone_dust"), 2), (rc("circuit_board"), 1)],
             rc("display_panel"), 1, 80)
    assembly("shutter_unit", [(mc("iron_ingot"), 2), (mc("redstone"), 1), (mc("piston"), 1)], rc("shutter_unit"), 1, 80)
    assembly("magnesium_alloy", [(mc("iron_ingot"), 1), (mc("copper_ingot"), 2)], rc("magnesium_alloy"), 2, 60)
    assembly("plastic", [(mc("slime_ball"), 1), (mc("black_dye"), 1), (mc("coal"), 1)], rc("plastic"), 4, 40)
    assembly("aperture_blades", [(mc("iron_nugget"), 6)], rc("aperture_blades"), 1, 40)
    assembly("af_motor", [(mc("copper_ingot"), 2), (mc("redstone"), 1), (mc("slime_ball"), 1)], rc("af_motor"), 1, 60)
    assembly("stabilizer", [(mc("compass"), 1), (mc("iron_ingot"), 1), (mc("ender_pearl"), 1)], rc("stabilizer"), 1, 100)
    assembly("battery_cell", [(mc("copper_ingot"), 1), (mc("redstone"), 2), (mc("glow_ink_sac"), 1)], rc("battery_cell"), 2, 60)
    assembly("flash_memory", [(rc("silicon_wafer"), 1), (mc("gold_nugget"), 2)], rc("flash_memory"), 2, 60)

    # --- catalog equipment
    assembly("sony_a7iv", [(rc("bsi_cmos_sensor"), 1), (rc("processor"), 2), (rc("magnesium_alloy"), 3),
                           (rc("shutter_unit"), 1), (rc("display_panel"), 2), (rc("stabilizer"), 1)],
             rc("camera_body"), 1, 400, {f"{NS}:body": f"{NS}:sony_a7iv"})
    assembly("sony_fe_28_70_oss", [(rc("lens_element"), 4), (rc("aperture_blades"), 1), (rc("af_motor"), 1),
                                   (rc("plastic"), 3), (rc("stabilizer"), 1)],
             rc("lens"), 1, 240, {f"{NS}:lens": f"{NS}:sony_fe_28_70_oss"})
    assembly("sony_fe_85_f18", [(rc("lens_element"), 5), (rc("aperture_blades"), 2), (rc("af_motor"), 1),
                                (rc("plastic"), 2), (mc("gold_nugget"), 2)],
             rc("lens"), 1, 260, {f"{NS}:lens": f"{NS}:sony_fe_85_f18"})
    assembly("np_fz100", [(rc("battery_cell"), 2), (rc("plastic"), 1), (rc("circuit_board"), 1)],
             rc("battery"), 1, 100, {f"{NS}:battery_type": f"{NS}:np_fz100", f"{NS}:charge": {"charge": 290, "capacity": 580}})
    card = {"used_bytes": 0, "photo_count": 0}
    assembly("sd_uhs1_64", [(rc("flash_memory"), 2), (rc("plastic"), 1), (rc("circuit_board"), 1)],
             rc("memory_card"), 1, 80, {f"{NS}:card_type": f"{NS}:sd_uhs1_64", f"{NS}:card": card})
    assembly("sd_uhs2_128", [(rc("flash_memory"), 4), (rc("plastic"), 1), (rc("circuit_board"), 1), (mc("gold_nugget"), 2)],
             rc("memory_card"), 1, 120, {f"{NS}:card_type": f"{NS}:sd_uhs2_128", f"{NS}:card": card})
    assembly("cfexpress_a_160", [(rc("flash_memory"), 5), (rc("processor"), 1), (rc("plastic"), 1), (rc("magnesium_alloy"), 1)],
             rc("memory_card"), 1, 160, {f"{NS}:card_type": f"{NS}:cfexpress_a_160", f"{NS}:card": card})

    # --- stage 2 catalog
    assembly("nikon_d750", [(rc("cmos_sensor"), 1), (rc("processor"), 2), (rc("magnesium_alloy"), 3), (rc("shutter_unit"), 1),
                            (mc("glass"), 2), (rc("display_panel"), 1)], rc("camera_body"), 1, 380, {f"{NS}:body": f"{NS}:nikon_d750"})
    assembly("leica_m11", [(rc("bsi_cmos_sensor"), 1), (rc("processor"), 1), (mc("copper_ingot"), 4),
                           (rc("shutter_unit"), 1), (rc("lens_element"), 2), (mc("diamond"), 1)], rc("camera_body"), 1, 500,
             {f"{NS}:body": f"{NS}:leica_m11"})
    assembly("sony_e_pz_16_50", [(rc("lens_element"), 3), (rc("aperture_blades"), 1), (rc("af_motor"), 1), (rc("plastic"), 2),
                                 (rc("stabilizer"), 1)], rc("lens"), 1, 200, {f"{NS}:lens": f"{NS}:sony_e_pz_16_50"})
    assembly("sony_fe_200_600_g", [(rc("ed_lens_element"), 4), (rc("lens_element"), 6), (rc("aperture_blades"), 2),
                                   (rc("af_motor"), 2), (rc("stabilizer"), 1), (rc("magnesium_alloy"), 3)],
             rc("lens"), 1, 400, {f"{NS}:lens": f"{NS}:sony_fe_200_600_g"})
    assembly("nikon_afs_50_f18g", [(rc("lens_element"), 4), (rc("aperture_blades"), 1), (rc("af_motor"), 1), (rc("plastic"), 2)],
             rc("lens"), 1, 200, {f"{NS}:lens": f"{NS}:nikon_afs_50_f18g"})
    assembly("canon_ef_50_f18_stm", [(rc("lens_element"), 4), (rc("aperture_blades"), 1), (rc("af_motor"), 1), (rc("plastic"), 2)],
             rc("lens"), 1, 200, {f"{NS}:lens": f"{NS}:canon_ef_50_f18_stm"})
    assembly("leica_summicron_m_50_f2", [(rc("lens_element"), 5), (rc("aperture_blades"), 1), (mc("copper_ingot"), 2),
                                         (mc("gold_nugget"), 3)], rc("lens"), 1, 300, {f"{NS}:lens": f"{NS}:leica_summicron_m_50_f2"})
    assembly("sigma_mc11_ef_e", [(rc("magnesium_alloy"), 1), (rc("circuit_board"), 1), (rc("processor"), 1)],
             rc("mount_adapter"), 1, 120, {f"{NS}:adapter": f"{NS}:sigma_mc11_ef_e"})
    assembly("f_to_e", [(mc("iron_ingot"), 2)], rc("mount_adapter"), 1, 60, {f"{NS}:adapter": f"{NS}:f_to_e"})
    assembly("m_to_e", [(mc("iron_ingot"), 1), (mc("copper_ingot"), 1)], rc("mount_adapter"), 1, 60, {f"{NS}:adapter": f"{NS}:m_to_e"})
    assembly("sony_sel14tc", [(rc("lens_element"), 3), (rc("magnesium_alloy"), 1), (rc("circuit_board"), 1)],
             rc("teleconverter"), 1, 160, {f"{NS}:teleconverter": f"{NS}:sony_sel14tc"})
    assembly("sony_sel20tc", [(rc("lens_element"), 4), (rc("magnesium_alloy"), 1), (rc("circuit_board"), 1)],
             rc("teleconverter"), 1, 180, {f"{NS}:teleconverter": f"{NS}:sony_sel20tc"})
    assembly("en_el15c", [(rc("battery_cell"), 2), (rc("plastic"), 1), (rc("circuit_board"), 1), (mc("redstone"), 1)],
             rc("battery"), 1, 100, {f"{NS}:battery_type": f"{NS}:en_el15c", f"{NS}:charge": {"charge": 615, "capacity": 1230}})
    assembly("bp_scl7", [(rc("battery_cell"), 2), (rc("magnesium_alloy"), 1), (rc("circuit_board"), 1)],
             rc("battery"), 1, 100, {f"{NS}:battery_type": f"{NS}:bp_scl7", f"{NS}:charge": {"charge": 350, "capacity": 700}})

    # --- stage 3: accessories
    for name, gn_items in (("sony_hvl_f46rm", 3), ("nikon_sb700", 2), ("canon_el5", 3), ("fuji_ef60", 3), ("godox_v1", 4)):
        assembly(name, [(rc("plastic"), 2), (rc("circuit_board"), 1), (mc("glowstone_dust"), gn_items), (rc("battery_cell"), 2)],
                 rc("flash"), 1, 160, {f"{NS}:flash_type": f"{NS}:{name}"})
    import catalog as _C
    for fl in _C.FILTERS:
        # bigger glass takes more of it
        glass = 1 if fl["size"] <= 82 else 2
        assembly(fl["key"], [(rc("optical_glass"), glass), (mc("iron_nugget"), 2), fl["extra"]], rc("filter"), 1, 60,
                 {f"{NS}:filter_type": f"{NS}:{fl['key']}"})
    assembly("sony_vg_c4em", [(rc("magnesium_alloy"), 2), (rc("circuit_board"), 1), (rc("battery_cell"), 2)], rc("battery_grip"), 1,
             160, {f"{NS}:grip_type": f"{NS}:sony_vg_c4em", f"{NS}:charge": {"charge": 290, "capacity": 580}})
    assembly("nikon_mb_d16", [(rc("magnesium_alloy"), 2), (rc("circuit_board"), 1), (rc("battery_cell"), 2)], rc("battery_grip"), 1,
             160, {f"{NS}:grip_type": f"{NS}:nikon_mb_d16", f"{NS}:charge": {"charge": 615, "capacity": 1230}})
    assembly("tripod", [(mc("iron_ingot"), 3), (rc("magnesium_alloy"), 2), (mc("leather"), 1)], rc("tripod"), 1, 120)
    assembly("camera_bag", [(mc("leather"), 4), (mc("white_wool"), 2), (mc("string"), 2)], rc("camera_bag"), 1, 80)
    assembly("cleaning_kit", [(mc("slime_ball"), 1), (mc("paper"), 2), (mc("glass_bottle"), 1)], rc("cleaning_kit"), 1, 40)

    # --- repairs (the result is a repaired copy of the input item)
    def repair(name, kind, ingredients, result):
        save(name, {"type": f"{NS}:assembly", "ingredients": [{"item": i, "count": n} for i, n in ingredients],
                    "result": {"id": result, "count": 1}, "time": 200, "repair": kind})
    repair("repair_shutter", "shutter", [(rc("camera_body"), 1), (rc("shutter_unit"), 1)], rc("camera_body"))
    repair("repair_electronics", "electronics", [(rc("camera_body"), 1), (rc("circuit_board"), 2), (rc("processor"), 1)],
           rc("camera_body"))
    repair("repair_lens", "lens", [(rc("lens"), 1), (rc("lens_element"), 1)], rc("lens"))
    repair("repair_filter", "filter", [(rc("filter"), 1), (rc("optical_glass"), 1)], rc("filter"))

    # --- devices
    assembly("battery_charger", [(rc("plastic"), 3), (rc("circuit_board"), 1), (mc("copper_ingot"), 2), (mc("redstone"), 1)],
             rc("battery_charger"), 1, 120)
    assembly("photo_printer", [(rc("plastic"), 4), (rc("circuit_board"), 2), (mc("iron_ingot"), 2), (rc("processor"), 1),
                               (rc("af_motor"), 1)], rc("photo_printer"), 1, 200)

    # --- consumables
    for colour, dye in (("cyan", "cyan_dye"), ("magenta", "magenta_dye"), ("yellow", "yellow_dye"), ("black", "black_dye")):
        assembly(f"ink_cartridge_{colour}", [(rc("plastic"), 1), (mc(dye), 2), (mc("glass_bottle"), 1)],
                 rc(f"ink_cartridge_{colour}"), 1, 40)
    for size, count in (("10x15", 8), ("a5", 4), ("a4", 2), ("a3", 1)):
        for finish, coating in (("glossy", "slime_ball"), ("matte", "bone_meal")):
            assembly(f"photo_paper_{size}_{finish}", [(mc("paper"), 4), (mc(coating), 1)], rc("photo_paper"), count, 40,
                     {f"{NS}:paper": {"size": size, "finish": finish}})

    # --- frames: material amount grows with the perimeter
    sizes = [(1, 1), (2, 1), (1, 2), (2, 2), (3, 2), (2, 3), (4, 3), (3, 4)]
    materials = {"oak": "oak_planks", "spruce": "spruce_planks", "dark_oak": "dark_oak_planks", "birch": "birch_planks",
                 "cherry": "cherry_planks", "bamboo": "bamboo_planks", "black_metal": "iron_ingot", "gold": "gold_ingot"}
    for mat, item in materials.items():
        for w, h in sizes:
            n = max(1, (w + h) // (2 if mat in ("black_metal", "gold") else 1))
            ing = [(mc(item), n), (mc("stick"), 2), (mc("glass_pane"), 1), (mc("paper"), 1)]
            if mat == "black_metal":
                ing.append((mc("black_dye"), 1))
            assembly(f"photo_frame_{mat}_{w}x{h}", ing, rc("photo_frame"), 1, 40 + 10 * (w + h),
                     {f"{NS}:frame": {"width": w, "height": h, "material": mat}})
    # --- stage 4: computer, album, displays
    assembly("computer", [(rc("plastic"), 4), (rc("display_panel"), 1), (rc("processor"), 1), (rc("circuit_board"), 2),
                          (rc("flash_memory"), 1), (mc("redstone"), 2)], rc("computer"), 1, 240)
    assembly("cfexpress_reader", [(rc("plastic"), 1), (rc("circuit_board"), 1), (mc("gold_ingot"), 1)],
             rc("cfexpress_reader"), 1, 80)
    save("photo_album", {"type": "minecraft:crafting_shapeless", "category": "misc",
                         "ingredients": [{"item": mc("book")}, {"item": mc("leather")}, {"item": mc("paper")},
                                         {"item": mc("paper")}, {"item": mc("paper")}],
                         "result": {"id": rc("photo_album"), "count": 1}})
    save("table_frame", {"type": "minecraft:crafting_shaped", "category": "misc",
                         "pattern": ["SPS", "S S", "SSS"],
                         "key": {"S": {"item": mc("stick")}, "P": {"item": mc("glass_pane")}},
                         "result": {"id": rc("table_frame"), "count": 2}})
    save("photo_stand", {"type": "minecraft:crafting_shaped", "category": "misc",
                         "pattern": [" S ", "SPS", "S S"],
                         "key": {"S": {"item": mc("stick")}, "P": {"tag": mc("planks")}},
                         "result": {"id": rc("photo_stand"), "count": 1}})

    # --- stage 5: film, darkroom
    stocks = {"kodak_portra_400": "orange_dye", "kodak_ektar_100": "red_dye", "kodak_gold_200": "yellow_dye",
              "kodak_tri_x_400": "black_dye", "ilford_hp5_plus_400": "white_dye", "ilford_delta_3200": "gray_dye",
              "fujifilm_velvia_50": "green_dye", "fujifilm_provia_100f": "lime_dye", "cinestill_800t": "blue_dye"}
    for stock, dye in stocks.items():
        # A roll does not stack (each carries its own frames), so one roll per craft. The emulsion is gelatine
        # (bone meal, as real photographic gelatine is made from bones) dyed per stock; 135 is wound on a metal
        # cassette, 120 on paper backing.
        for fmt in ("135", "120"):
            spool = (mc("iron_nugget"), 1) if fmt == "135" else (mc("paper"), 1)
            assembly(f"film_{stock}_{fmt}", [(mc("paper"), 1), (mc("bone_meal"), 1), (mc(dye), 1), spool],
                     rc("film_roll"), 1, 60,
                     {f"{NS}:film_stock": rc(stock), f"{NS}:film": {"format": fmt, "state": 0}})
    for kit, extra in (("c41", "orange_dye"), ("e6", "blue_dye"), ("bw", "charcoal")):
        assembly(f"chemistry_{kit}", [(mc("glass_bottle"), 3), (mc("sugar"), 1), (mc("redstone"), 1), (mc(extra), 1)],
                 rc(f"chemistry_{kit}"), 1, 80)
    assembly("changing_bag", [(mc("black_wool"), 2), (mc("leather"), 1)], rc("changing_bag"), 1, 60)
    assembly("film_scanner", [(rc("plastic"), 2), (rc("cmos_sensor"), 1), (rc("lens_element"), 1), (rc("circuit_board"), 1)],
             rc("film_scanner"), 1, 160)
    assembly("developing_tank", [(rc("plastic"), 3), (mc("iron_nugget"), 2)], rc("developing_tank"), 1, 80)
    assembly("enlarger", [(mc("iron_ingot"), 4), (rc("lens_element"), 1), (mc("glowstone_dust"), 2), (mc("smooth_stone_slab"), 1)],
             rc("enlarger"), 1, 200)
    assembly("safelight", [(mc("iron_nugget"), 2), (mc("red_stained_glass"), 1), (mc("glowstone_dust"), 1)],
             rc("safelight"), 1, 40)
    for size, count in (("10x15", 8), ("a5", 4), ("a4", 2), ("a3", 1)):
        for finish, coating in (("rc_bw", "bone_meal"), ("fiber_bw", "white_wool"), ("ra4", "glowstone_dust")):
            assembly(f"photo_paper_{size}_{finish}", [(mc("paper"), 4), (mc(coating), 1), (mc("slime_ball"), 1)],
                     rc("photo_paper"), count, 40, {f"{NS}:paper": {"size": size, "finish": finish}})
    for body, mats in (("nikon_fm2", [(rc("magnesium_alloy"), 2), (rc("shutter_unit"), 1), (rc("aperture_blades"), 1), (mc("iron_ingot"), 2)]),
                       ("leica_m6", [(rc("magnesium_alloy"), 2), (rc("shutter_unit"), 1), (rc("lens_element"), 2), (mc("gold_ingot"), 1)]),
                       ("canon_eos_1v", [(rc("magnesium_alloy"), 3), (rc("shutter_unit"), 1), (rc("af_motor"), 1), (rc("processor"), 1), (mc("diamond"), 1)])):
        assembly(f"body_{body}", mats, rc("camera_body"), 1, 300, {f"{NS}:body": rc(body)})
    for bat, cap, mats in (("sr44", 4000, [(rc("battery_cell"), 1), (mc("iron_nugget"), 1)]),
                           ("cr2", 1500, [(rc("battery_cell"), 2), (rc("plastic"), 1)])):
        assembly(f"battery_{bat}", mats, rc("battery"), 1, 60,
                 {f"{NS}:battery_type": rc(bat), f"{NS}:charge": {"charge": cap, "capacity": cap}})
    stage6_catalog()
    # --- stage 7: encyclopedia and the newspaper
    save("editorial_desk", {"type": "minecraft:crafting_shaped", "category": "misc",
                            "pattern": ["PBP", "SSS", "S S"],
                            "key": {"P": {"item": mc("paper")}, "B": {"item": mc("ink_sac")}, "S": {"tag": mc("planks")}},
                            "result": {"id": rc("editorial_desk"), "count": 1}})
    save("field_journal", {"type": "minecraft:crafting_shapeless", "category": "misc",
                           "ingredients": [{"item": mc("book")}, {"item": mc("ink_sac")}, {"item": mc("feather")},
                                           {"item": mc("leather")}],
                           "result": {"id": rc("field_journal"), "count": 1}})
    print(len(os.listdir(OUT)), "recipes written")

    # --- block loot tables (each block drops itself; the photo stand only from its lower half)
    shutil.rmtree(LOOT, ignore_errors=True)
    os.makedirs(LOOT)
    for block in ("assembly_station", "battery_charger", "photo_printer", "computer", "table_frame", "photo_stand",
                  "developing_tank", "enlarger", "safelight", "editorial_desk"):
        entry = {"type": "minecraft:item", "name": rc(block)}
        pool = {"rolls": 1, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}
        if block == "photo_stand":
            pool["conditions"].append({"condition": "minecraft:block_state_property", "block": rc(block),
                                       "properties": {"half": "lower"}})
        with open(os.path.join(LOOT, block + ".json"), "w") as f:
            json.dump({"type": "minecraft:block", "pools": [pool]}, f, indent=2)
            f.write("\n")
    print(len(os.listdir(LOOT)), "loot tables written")


if __name__ == "__main__":
    main()
