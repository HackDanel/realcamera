#!/usr/bin/env python3
"""
Generates RealCamera recipes into src/main/resources/data/realcamera/recipe/.
Run from the project root: python3 tools/gen_recipes.py   (old generated files are removed first)

Assembly recipes ("realcamera:assembly") run in the Photo Workshop: shapeless, with counts,
the result may carry data components, "time" is in ticks.
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "src/main/resources/data/realcamera/recipe")
NS = "realcamera"


def mc(name):
    return "minecraft:" + name


def rc(name):
    return f"{NS}:" + name


def save(name, obj):
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(obj, f, indent=2)
        f.write("\n")


def assembly(name, ingredients, result, count=1, time=100, components=None):
    res = {"id": result, "count": count}
    if components:
        res["components"] = components
    save(name, {
        "type": f"{NS}:assembly",
        "ingredients": [{"item": item, "count": n} for item, n in ingredients],
        "result": res,
        "time": time,
    })


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
    for name, extra in (("nd8_82", (mc("black_dye"), 1)), ("nd64_82", (mc("black_dye"), 2)), ("nd1000_82", (mc("black_dye"), 4)),
                        ("cpl_82", (mc("amethyst_shard"), 1)), ("uv_82", (mc("quartz"), 1)), ("yellow_82", (mc("yellow_dye"), 1)),
                        ("orange_82", (mc("orange_dye"), 1)), ("red_82", (mc("red_dye"), 1)), ("cpl_95", (mc("amethyst_shard"), 2))):
        assembly(name, [(rc("optical_glass"), 1), (mc("iron_nugget"), 2), extra], rc("filter"), 1, 60,
                 {f"{NS}:filter_type": f"{NS}:{name}"})
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
    print(len(os.listdir(OUT)), "recipes written")


if __name__ == "__main__":
    main()
