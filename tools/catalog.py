#!/usr/bin/env python3
"""
Stage 6 catalog: the full line-up of bodies, lenses, mounts, adapters, teleconverters, batteries and cards
(plan §10), as compact Python data. Read by:
  tools/gen_catalog.py  -> data/realcamera/realcamera/<registry>/*.json + lang keys
  tools/gen_assets.py   -> item models and textures (style/look fields)
  tools/gen_recipes.py  -> assembly recipes (tier / kind)
Entries made in earlier stages are hand-written JSON and are not listed here.
"""

NS = "realcamera"


def rc(name):
    return f"{NS}:{name}"


# ----------------------------------------------------------------------------- mounts
# key: (display name, flange mm, throat mm)
MOUNTS = {
    "nikon_z": ("Nikon Z", 16.0, 55.0),
    "canon_rf": ("Canon RF", 20.0, 54.0),
    "canon_fd": ("Canon FD", 42.0, 48.0),
    "fuji_x": ("Fujifilm X", 17.7, 43.5),
    "fuji_g": ("Fujifilm G", 26.7, 65.0),
    "l_mount": ("L-Mount", 20.0, 51.6),
    "mft": ("Micro Four Thirds", 19.25, 38.0),
    "pentax_k": ("Pentax K", 45.46, 44.0),
    "olympus_om": ("Olympus OM", 46.0, 41.0),
    "m42": ("M42", 45.46, 42.0),
    "hasselblad_v": ("Hasselblad V", 74.9, 60.0),
    "hasselblad_xcd": ("Hasselblad XCD", 18.14, 60.0),
    "fixed": ("—", 0.0, 0.0),  # built-in lenses of compact cameras
}
# the same mount under its APS-C name (lang key "realcamera.mount.realcamera.<key>.apsc"): shown for crop bodies and
# APS-C lenses; mounts without one (Nikon F/Z DX, Sony E, Fujifilm X) keep their name
APSC_MOUNT_NAMES = {"canon_ef": "Canon EF-S", "canon_rf": "Canon RF-S"}
OLD_MOUNT_FLANGE = {"sony_e": 18.0, "canon_ef": 44.0, "nikon_f": 46.5, "leica_m": 27.8}


# capacities of the batteries made in earlier stages (hand-written JSON)
OLD_BATTERY_CAPACITY = {"np_fz100": 580, "en_el15c": 1230, "bp_scl7": 700, "sr44": 4000, "cr2": 1500}


def flange(mount):
    return OLD_MOUNT_FLANGE.get(mount, MOUNTS.get(mount, (0, 0))[1])


# ----------------------------------------------------------------------------- batteries and cards
# key: (display name, capacity in shots, icon body colour, icon stripe colour)
BATTERIES = {
    "np_fw50": ("Sony NP-FW50", 420, "#2a2a2c", "#c8c8c8"),
    "en_el14a": ("Nikon EN-EL14a", 1500, "#2a2a2c", "#e0c040"),
    "en_el25": ("Nikon EN-EL25", 300, "#2a2a2c", "#e0c040"),
    "en_el18d": ("Nikon EN-EL18d", 740, "#2a2a2c", "#e0c040"),
    "lp_e6nh": ("Canon LP-E6NH", 580, "#2a2a2c", "#d02020"),
    "lp_e17": ("Canon LP-E17", 400, "#2a2a2c", "#d02020"),
    "lp_e19": ("Canon LP-E19", 900, "#2a2a2c", "#d02020"),
    "np_w126s": ("Fujifilm NP-W126S", 380, "#3a3a3c", "#d0d0d0"),
    "np_w235": ("Fujifilm NP-W235", 580, "#3a3a3c", "#d0d0d0"),
    "np_t125": ("Fujifilm NP-T125", 540, "#3a3a3c", "#d0d0d0"),
    "dmw_blk22": ("Panasonic DMW-BLK22", 370, "#2a2a2c", "#5070d0"),
    "blx_1": ("OM System BLX-1", 500, "#2a2a2c", "#40a0d0"),
    "bp_scl6": ("Leica BP-SCL6", 350, "#3a3a3c", "#d02020"),
    "hasselblad_x2d_battery": ("Hasselblad X2D Battery", 420, "#4a4a4c", "#e0e0e0"),
    "px28": ("4LR44", 4000, "#9a9aa0", "#404040"),
    "px625": ("PX625", 4000, "#9a9aa0", "#404040"),
    "cr2_lithium": ("CR2", 1200, "#202020", "#e09030"),
    "lp_e6p": ("Canon LP-E6P", 590, "#2a2a2c", "#d02020"),
    "sr44_single": ("SR44", 2000, "#9a9aa0", "#404040"),
}

# key: (display name, form, capacity GB, write MB/s, icon model)
CARDS = {
    "sd_uhs1_128": ("SD UHS-I 128 GB", "sd", 128, 90, "sd_uhs1"),
    "sd_uhs2_256": ("SD UHS-II 256 GB", "sd", 256, 280, "sd_uhs2"),
    "cfexpress_b_256": ("CFexpress Type B 256 GB", "cfexpress_b", 256, 1500, "cfexpress_b"),
    "cfexpress_b_512": ("CFexpress Type B 512 GB", "cfexpress_b", 512, 1700, "cfexpress_b"),
    "cf_udma7_128": ("CompactFlash UDMA 7 128 GB", "cf", 128, 150, "cf"),
    # built into the body (Hasselblad X2D): never crafted, the body comes with it; read over USB by the computer
    "hasselblad_x2d_ssd": ("Hasselblad X2D internal SSD 1 TB", "internal_x2d", 1024, 2000, "internal_ssd"),
}

# ----------------------------------------------------------------------------- colour profiles
PROFILES = {
    "sony": ["sony_st", "sony_pt", "sony_vv", "sony_fl", "sony_bw"],
    "nikon": ["nikon_sd", "nikon_vi", "nikon_fl", "nikon_mc", "nikon_rtp"],
    "canon": ["canon_standard", "canon_portrait", "canon_landscape", "canon_fine_detail", "canon_monochrome"],
    "fuji": ["fuji_provia", "fuji_velvia", "fuji_astia", "fuji_classic_chrome", "fuji_classic_neg", "fuji_eterna", "fuji_acros"],
    "leica": ["leica_std", "leica_vivid", "leica_mono"],
    "hasselblad": ["hasselblad_hnc"],
    "panasonic": ["panasonic_standard", "panasonic_vivid", "panasonic_l_monochrome"],
    "om": ["om_natural", "om_vivid", "om_monotone"],
}
# new profiles: key -> (name, contrast, saturation, monochrome, tint_r, tint_g, tint_b, shadow_lift)
NEW_PROFILES = {
    "panasonic_standard": ("Standard", 1.05, 1.05, False, 1.0, 1.0, 1.0, 0.0),
    "panasonic_vivid": ("Vivid", 1.12, 1.25, False, 1.0, 1.0, 1.0, 0.0),
    "panasonic_l_monochrome": ("L.Monochrome", 1.18, 0.0, True, 1.0, 1.0, 1.0, 0.0),
    "om_natural": ("Natural", 1.04, 1.06, False, 1.0, 1.0, 1.01, 0.0),
    "om_vivid": ("Vivid", 1.1, 1.22, False, 1.0, 1.0, 1.0, 0.0),
    "om_monotone": ("Monotone", 1.12, 0.0, True, 1.0, 1.0, 1.0, 0.0),
}

# ----------------------------------------------------------------------------- bodies

FF = (35.9, 23.9)
APSC = (23.5, 15.6)
APSC_CANON = (22.3, 14.9)
MFT = (17.3, 13.0)
MF = (43.8, 32.9)
F135 = (36.0, 24.0)
F120_66 = (56.0, 56.0)


def body(key, name, brand, make, model, tier, btype, mount, sensor, mp, iso, dr, noise, vf, shutter, af, ibis,
         battery, cards, sealing, profiles, flash=(0, 1 / 200), film=None, fixed_lens=None, look=None, drain=1.0,
         cipa=None, shutter_extra=None, film_extra=None):
    """
    sensor: (w, h); iso: (base, min, max); noise: (luma, chroma); shutter: (mech_min, elec_min, max, life, sound,
    readout_ms, burst_mech, burst_elec, buffer, efcs); af: (speed, low_light_ev, detect); look: model style/colours.
    cipa: CIPA shots per charge (viewfinder use) -> battery_drain = battery capacity / cipa.
    shutter_extra: e.g. {"leaf": True} or {"full_mech": False}; film_extra: {"modes": [...], "meter": "...", "iso_dial": "..."}.
    """
    if cipa:
        drain = round((BATTERIES[battery][1] if battery in BATTERIES else OLD_BATTERY_CAPACITY[battery]) / cipa, 3)
    mech, elec, slow, life, sound, readout, bm, be, buf, efcs = shutter
    spec = {
        "info": {"brand": brand, "exif_make": make, "exif_model": model, "tier": tier, "type": btype},
        "mount": rc(mount),
        "sensor": {"width_mm": sensor[0], "height_mm": sensor[1], "megapixels": mp, "base_iso": iso[0],
                   "iso_min": iso[1], "iso_max": iso[2], "dynamic_range_ev": dr, "noise_luma": noise[0],
                   "noise_chroma": noise[1]},
        "viewfinder": vf,
        "shutter": {"mech_min": mech, "elec_min": elec, "max": slow, "rated_life": life, "sound": sound,
                    "readout_ms": readout, "burst_mech": bm, "burst_elec": be, "buffer_shots": buf, "efcs": efcs},
        "af": {"speed": af[0], "low_light_ev": af[1], "subject_detect": af[2]},
        "ibis_stops": ibis,
        "battery_type": rc(battery),
        "battery_drain": drain,
        "card_forms": cards,
        "weather_sealing": sealing,
        "color_profiles": [rc(p) for p in PROFILES.get(profiles, [])],
        "model": rc(f"item/body/{key}"),
        "flash": {"built_in_gn": flash[0], "sync": flash[1]},
    }
    if shutter_extra:
        spec["shutter"].update(shutter_extra)
    if film:
        spec["film"] = {"format": film[0], "mechanical": film[1]}
        if film_extra:
            spec["film"].update(film_extra)
    if fixed_lens:
        spec["fixed_lens"] = rc(fixed_lens)
    return {"key": key, "name": name, "spec": spec, "tier": tier, "look": look or {}}


def look(style, brand_text, accent=(200, 40, 30), text=None, top=(40, 40, 42), body_c=(34, 34, 36)):
    return {"style": style, "brand": brand_text, "accent": accent, "text": text, "top": top, "body": body_c}


SILVER = (188, 190, 194)
BLACK = (36, 36, 38)
TITAN = (170, 160, 140)

MIRRORLESS = "mirrorless"
S8000 = 1 / 8000
S4000 = 1 / 4000
NO_FLASH = (0, 1 / 250)

BODIES = [
    # ---------------------------------------------------------------- Sony
    body("sony_zv_e10", "Sony ZV-E10", "Sony", "SONY", "ZV-E10", "entry", "mirrorless", "sony_e", APSC, 24.2,
         (100, 100, 32000), 13.0, (1.15, 1.1), "none", (S4000, S4000, 30, 100000, MIRRORLESS, 50, 11, 11, 116, True),
         (0.9, -3, "eye"), 0, "np_fw50", ["sd"], 0, "sony", (0, 1 / 160),
         look=look("flat", "SONY", (210, 60, 40), "ZV"), cipa=440),
    body("sony_a6400", "Sony α6400", "Sony", "SONY", "ILCE-6400", "mid", "mirrorless", "sony_e", APSC, 24.2,
         (100, 100, 32000), 13.5, (1.1, 1.05), "evf", (S4000, S4000, 30, 200000, MIRRORLESS, 45, 11, 8, 99, True),
         (1.0, -2, "eye"), 0, "np_fw50", ["sd"], 1, "sony", (6, 1 / 160),
         look=look("flat", "SONY", (210, 60, 40), "6400"), cipa=360),
    body("sony_a7rv", "Sony α7R V", "Sony", "SONY", "ILCE-7RM5", "pro", "mirrorless", "sony_e", (35.7, 23.8), 61.0,
         (100, 100, 32000), 15.0, (1.1, 1.05), "evf", (S8000, S8000, 30, 500000, MIRRORLESS, 100, 10, 7, 583, True),
         (1.1, -4, "eye"), 8.0, "np_fz100", ["sd", "cfexpress_a"], 1, "sony", (0, 1 / 250),
         look=look(MIRRORLESS, "SONY", (210, 60, 40), "7RV"), cipa=440),
    body("sony_a1ii", "Sony α1 II", "Sony", "SONY", "ILCE-1M2", "flagship", "mirrorless", "sony_e", (35.9, 24.0), 50.1,
         (100, 100, 32000), 15.0, (1.0, 1.0), "evf", (S8000, 1 / 32000, 30, 500000, MIRRORLESS, 4, 10, 30, 1000, True),
         (1.4, -4, "vehicle"), 8.5, "np_fz100", ["sd", "cfexpress_a"], 2, "sony", (0, 1 / 400),
         look=look(MIRRORLESS, "SONY", (232, 118, 30), "α1II"), cipa=420),
    # ---------------------------------------------------------------- Nikon
    body("nikon_d3500", "Nikon D3500", "Nikon", "NIKON CORPORATION", "NIKON D3500", "entry", "dslr", "nikon_f", APSC, 24.2,
         (100, 100, 25600), 13.5, (1.15, 1.1), "ovf", (S4000, S4000, 30, 100000, "dslr", 0, 5, 5, 100, False),
         (0.75, -0.5, "none"), 0, "en_el14a", ["sd"], 0, "nikon", (12, 1 / 200),
         look=look("dslr", "NIKON", (200, 40, 30), "D3500"), cipa=1550),
    body("nikon_z50ii", "Nikon Z50II", "Nikon", "NIKON CORPORATION", "NIKON Z50_2", "entry", "mirrorless", "nikon_z", APSC, 20.9,
         (100, 100, 51200), 13.0, (1.1, 1.05), "evf", (S4000, S4000, 30, 150000, MIRRORLESS, 40, 11, 30, 100, True),
         (1.0, -7, "eye"), 0, "en_el25", ["sd"], 1, "nikon", (7, 1 / 200),
         look=look(MIRRORLESS, "NIKON", (230, 190, 40), "Z50II"), cipa=250),
    body("nikon_z6iii", "Nikon Z6III", "Nikon", "NIKON CORPORATION", "NIKON Z6_3", "mid", "mirrorless", "nikon_z", FF, 24.5,
         (100, 100, 64000), 13.8, (0.95, 0.95), "evf", (S8000, 1 / 16000, 900, 200000, MIRRORLESS, 14, 14, 20, 200, True),
         (1.2, -10, "eye"), 8.0, "en_el15c", ["sd", "cfexpress_b"], 2, "nikon", (0, 1 / 200),
         look=look(MIRRORLESS, "NIKON", (230, 190, 40), "Z6III"), cipa=360),
    body("nikon_zf", "Nikon Zf", "Nikon", "NIKON CORPORATION", "NIKON Z f", "mid", "mirrorless", "nikon_z", FF, 24.5,
         (100, 100, 64000), 14.5, (0.95, 0.95), "evf", (S8000, S8000, 900, 200000, MIRRORLESS, 50, 7.8, 14, 100, True),
         (1.1, -10, "eye"), 8.0, "en_el15c", ["sd"], 1, "nikon", (0, 1 / 200),
         look=look("retro", "Nikon", (230, 190, 40), "Zf", top=SILVER), cipa=360),
    body("nikon_z8", "Nikon Z8", "Nikon", "NIKON CORPORATION", "NIKON Z 8", "pro", "mirrorless", "nikon_z", FF, 45.7,
         (64, 64, 25600), 14.5, (1.0, 1.0), "evf", (0, 1 / 32000, 900, 0, MIRRORLESS, 4, 20, 20, 1000, False),
         (1.4, -9, "vehicle"), 6.0, "en_el15c", ["sd", "cfexpress_b"], 2, "nikon", (0, 1 / 200),
         look=look(MIRRORLESS, "NIKON", (230, 190, 40), "Z8"), cipa=330),
    body("nikon_z9", "Nikon Z9", "Nikon", "NIKON CORPORATION", "NIKON Z 9", "flagship", "mirrorless", "nikon_z", FF, 45.7,
         (64, 64, 25600), 14.5, (1.0, 1.0), "evf", (0, 1 / 32000, 900, 0, MIRRORLESS, 4, 20, 30, 1000, False),
         (1.5, -9, "vehicle"), 6.0, "en_el18d", ["cfexpress_b"], 2, "nikon", (0, 1 / 200),
         look=look("pro", "NIKON", (230, 190, 40), "Z9"), cipa=740),
    # ---------------------------------------------------------------- Canon
    body("canon_eos_250d", "Canon EOS 250D", "Canon", "Canon", "Canon EOS 250D", "entry", "dslr", "canon_ef", APSC_CANON, 24.1,
         (100, 100, 25600), 12.5, (1.2, 1.15), "ovf", (S4000, S4000, 30, 100000, "dslr", 0, 5, 5, 40, False),
         (0.75, -0.5, "none"), 0, "lp_e17", ["sd"], 0, "canon", (9.8, 1 / 200),
         look=look("dslr", "Canon", (200, 30, 30), "250D"), cipa=1070),
    body("canon_eos_5d4", "Canon EOS 5D Mark IV", "Canon", "Canon", "Canon EOS 5D Mark IV", "pro", "dslr", "canon_ef", (36.0, 24.0), 30.4,
         (100, 100, 32000), 13.6, (1.0, 1.0), "ovf", (S8000, S8000, 30, 150000, "dslr", 0, 7, 7, 21, False),
         (1.0, -3, "face"), 0, "lp_e6nh", ["cf", "sd"], 2, "canon", (0, 1 / 200),
         look=look("dslr", "Canon", (200, 30, 30), "5D IV"), cipa=900),
    body("canon_eos_r50", "Canon EOS R50", "Canon", "Canon", "Canon EOS R50", "entry", "mirrorless", "canon_rf", APSC_CANON, 24.2,
         (100, 100, 32000), 12.5, (1.15, 1.1), "evf", (S4000, S8000, 30, 100000, MIRRORLESS, 40, 12, 15, 28, True),
         (1.0, -4, "eye"), 0, "lp_e17", ["sd"], 0, "canon", (7, 1 / 200),
         look=look(MIRRORLESS, "Canon", (200, 30, 30), "R50"), cipa=230, shutter_extra={"full_mech": False}),
    body("canon_eos_r8", "Canon EOS R8", "Canon", "Canon", "Canon EOS R8", "mid", "mirrorless", "canon_rf", (35.9, 23.9), 24.2,
         (100, 100, 102400), 14.0, (0.95, 0.95), "evf", (S4000, 1 / 16000, 30, 200000, MIRRORLESS, 25, 6, 40, 56, True),
         (1.1, -6.5, "eye"), 0, "lp_e17", ["sd"], 1, "canon", (0, 1 / 180),
         look=look(MIRRORLESS, "Canon", (200, 30, 30), "R8"), cipa=220, shutter_extra={"full_mech": False}),
    body("canon_eos_r6ii", "Canon EOS R6 Mark II", "Canon", "Canon", "Canon EOS R6m2", "mid", "mirrorless", "canon_rf", (35.9, 23.9), 24.2,
         (100, 100, 102400), 14.5, (0.92, 0.92), "evf", (S8000, 1 / 16000, 30, 300000, MIRRORLESS, 20, 12, 40, 140, True),
         (1.2, -6.5, "eye"), 8.0, "lp_e6nh", ["sd"], 1, "canon", (0, 1 / 250),
         look=look(MIRRORLESS, "Canon", (200, 30, 30), "R6 II"), cipa=360),
    body("canon_eos_r5ii", "Canon EOS R5 Mark II", "Canon", "Canon", "Canon EOS R5m2", "pro", "mirrorless", "canon_rf", (36.0, 24.0), 45.0,
         (100, 100, 51200), 14.5, (1.0, 1.0), "evf", (S8000, 1 / 32000, 30, 500000, MIRRORLESS, 6, 12, 30, 230, True),
         (1.4, -7.5, "vehicle"), 8.5, "lp_e6p", ["sd", "cfexpress_b"], 2, "canon", (0, 1 / 250),
         look=look(MIRRORLESS, "Canon", (200, 30, 30), "R5 II"), cipa=340),
    body("canon_eos_r1", "Canon EOS R1", "Canon", "Canon", "Canon EOS R1", "flagship", "mirrorless", "canon_rf", (36.0, 24.0), 24.2,
         (100, 100, 102400), 15.0, (0.9, 0.9), "evf", (S8000, 1 / 64000, 30, 500000, MIRRORLESS, 5, 12, 40, 1000, True),
         (1.6, -7.5, "vehicle"), 8.5, "lp_e19", ["cfexpress_b"], 2, "canon", (0, 1 / 250),
         look=look("pro", "Canon", (200, 30, 30), "R1"), cipa=700),
    # ---------------------------------------------------------------- Fujifilm
    body("fuji_xt30ii", "Fujifilm X-T30 II", "Fujifilm", "FUJIFILM", "X-T30 II", "entry", "mirrorless", "fuji_x", APSC, 26.1,
         (160, 160, 12800), 13.0, (1.1, 1.0), "evf", (S4000, 1 / 32000, 900, 100000, MIRRORLESS, 50, 8, 20, 90, False),
         (0.95, -7, "face"), 0, "np_w126s", ["sd"], 0, "fuji", (7, 1 / 180),
         look=look("retro", "FUJIFILM", (120, 120, 124), "X-T30", top=SILVER), cipa=390),
    body("fuji_xs20", "Fujifilm X-S20", "Fujifilm", "FUJIFILM", "X-S20", "mid", "mirrorless", "fuji_x", APSC, 26.1,
         (160, 160, 12800), 13.0, (1.05, 1.0), "evf", (S4000, 1 / 32000, 900, 150000, MIRRORLESS, 40, 8, 30, 120, True),
         (1.05, -7, "animal"), 7.0, "np_w235", ["sd"], 0, "fuji", (7, 1 / 180),
         look=look(MIRRORLESS, "FUJIFILM", (120, 120, 124), "X-S20"), cipa=750),
    body("fuji_xt5", "Fujifilm X-T5", "Fujifilm", "FUJIFILM", "X-T5", "mid", "mirrorless", "fuji_x", APSC, 40.2,
         (125, 125, 12800), 13.5, (1.1, 1.0), "evf", (S8000, 1 / 180000, 900, 500000, MIRRORLESS, 60, 15, 20, 110, True),
         (1.05, -7, "animal"), 7.0, "np_w235", ["sd"], 1, "fuji", (0, 1 / 250),
         look=look("retro", "FUJIFILM", (120, 120, 124), "X-T5", top=SILVER), cipa=580),
    body("fuji_xh2s", "Fujifilm X-H2S", "Fujifilm", "FUJIFILM", "X-H2S", "pro", "mirrorless", "fuji_x", APSC, 26.1,
         (160, 160, 12800), 12.5, (1.05, 1.0), "evf", (S8000, 1 / 32000, 900, 500000, MIRRORLESS, 6, 15, 40, 1000, True),
         (1.3, -7, "vehicle"), 7.0, "np_w235", ["sd", "cfexpress_b"], 2, "fuji", (0, 1 / 250),
         look=look(MIRRORLESS, "FUJIFILM", (120, 120, 124), "X-H2S"), cipa=580),
    body("fuji_x100vi", "Fujifilm X100VI", "Fujifilm", "FUJIFILM", "X100VI", "pro", "fixed_lens", "fixed", APSC, 40.2,
         (125, 125, 12800), 13.5, (1.1, 1.0), "hybrid", (S4000, 1 / 32000, 900, 300000, "leaf", 60, 11, 20, 60, False),
         (0.95, -5, "animal"), 6.0, "np_w126s", ["sd"], 0, "fuji", (4.4, S4000),
         fixed_lens="fuji_x100vi_23", look=look("compact", "FUJIFILM", (120, 120, 124), "X100VI", top=SILVER), cipa=310, shutter_extra={"leaf": True}),
    body("fuji_gfx100ii", "Fujifilm GFX100 II", "Fujifilm", "FUJIFILM", "GFX100 II", "flagship", "medium_format", "fuji_g", MF, 102.0,
         (80, 80, 12800), 15.5, (0.95, 0.95), "evf", (S4000, 1 / 16000, 3600, 300000, MIRRORLESS, 120, 8, 8, 300, True),
         (1.0, -5.5, "animal"), 8.0, "np_t125", ["sd", "cfexpress_b"], 2, "fuji", (0, 1 / 125),
         look=look("big", "FUJIFILM", (120, 120, 124), "GFX"), cipa=540),
    # ---------------------------------------------------------------- others
    body("panasonic_s5ii", "Panasonic Lumix S5II", "Panasonic", "Panasonic", "DC-S5M2", "mid", "mirrorless", "l_mount", FF, 24.2,
         (100, 100, 51200), 14.0, (1.0, 1.0), "evf", (S8000, 1 / 8000, 1800, 200000, MIRRORLESS, 25, 9, 30, 300, True),
         (1.0, -6, "animal"), 6.5, "dmw_blk22", ["sd"], 1, "panasonic", (0, 1 / 250),
         look=look(MIRRORLESS, "LUMIX", (200, 30, 30), "S5II"), cipa=370),
    body("leica_q3", "Leica Q3", "Leica", "LEICA CAMERA AG", "LEICA Q3", "flagship", "fixed_lens", "fixed", FF, 60.3,
         (100, 50, 100000), 15.0, (1.05, 1.0), "evf", (1 / 2000, 1 / 16000, 120, 200000, "leaf", 90, 15, 15, 30, False),
         (1.0, -6, "face"), 0, "bp_scl6", ["sd"], 1, "leica", (0, 1 / 2000),
         fixed_lens="leica_q3_28", look=look("compact", "LEICA", (210, 30, 30), "Q3", top=BLACK), cipa=350, shutter_extra={"leaf": True}),
    body("om_om1ii", "OM System OM-1 Mark II", "OM System", "OM Digital Solutions", "OM-1MarkII", "pro", "mirrorless", "mft", MFT, 20.4,
         (200, 80, 102400), 12.5, (1.25, 1.2), "evf", (S8000, 1 / 32000, 60, 400000, MIRRORLESS, 4, 10, 120, 256, True),
         (1.3, -5.5, "vehicle"), 8.5, "blx_1", ["sd"], 2, "om", (0, 1 / 250),
         look=look(MIRRORLESS, "OM", (40, 120, 200), "OM-1"), cipa=500),
    body("hasselblad_x2d", "Hasselblad X2D 100C", "Hasselblad", "Hasselblad", "X2D 100C", "flagship", "medium_format", "hasselblad_xcd", MF, 100.0,
         (64, 64, 25600), 15.0, (0.95, 0.95), "evf", (1 / 2000, 1 / 6000, 3600, 200000, "leaf", 150, 3.3, 3.3, 40, False),
         (0.8, -4, "none"), 7.0, "hasselblad_x2d_battery", ["internal_x2d"], 1, "hasselblad", (0, 1 / 2000),
         look=look("big", "HASSELBLAD", (200, 200, 205), "X2D", top=(120, 120, 124), body_c=(110, 110, 114)), cipa=420, shutter_extra={"leaf": True}),
    # ---------------------------------------------------------------- film
    body("canon_ae1p", "Canon AE-1 Program", "Canon", "Canon", "AE-1 PROGRAM", "entry", "film_slr", "canon_fd", F135, 24.0,
         (100, 12, 3200), 12.0, (0, 0), "ovf", (1 / 1000, 1 / 1000, 2, 100000, "dslr", 0, 1.5, 1.5, 1, False),
         (0, 99, "none"), 0, "px28", [], 0, "", (0, 1 / 60), film=("135", False), drain=0.3,
         look=look("retro_slr", "Canon", (200, 30, 30), "AE-1", top=SILVER), film_extra={"modes": ["p", "s", "m"]}),
    body("pentax_k1000", "Pentax K1000", "Pentax", "ASAHI OPT. CO.", "K1000", "entry", "film_slr", "pentax_k", F135, 24.0,
         (100, 20, 3200), 12.0, (0, 0), "ovf", (1 / 1000, 1 / 1000, 1, 100000, "dslr", 0, 1.5, 1.5, 1, False),
         (0, 99, "none"), 0, "sr44_single", [], 0, "", (0, 1 / 60), film=("135", True), drain=0.2,
         look=look("retro_slr", "ASAHI", (40, 40, 40), "K1000", top=SILVER), film_extra={"modes": ["m"]}),
    body("olympus_om1", "Olympus OM-1", "Olympus", "OLYMPUS", "OM-1", "mid", "film_slr", "olympus_om", F135, 24.0,
         (100, 25, 1600), 12.0, (0, 0), "ovf", (1 / 1000, 1 / 1000, 1, 100000, "dslr", 0, 1.5, 1.5, 1, False),
         (0, 99, "none"), 0, "px625", [], 0, "", (0, 1 / 60), film=("135", True), drain=0.2,
         look=look("retro_slr", "OLYMPUS", (40, 40, 40), "OM-1", top=SILVER), film_extra={"modes": ["m"]}),
    body("zenit_e", "Zenit-E", "KMZ", "KMZ", "ZENIT-E", "entry", "film_slr", "m42", F135, 24.0,
         (100, 16, 500), 12.0, (0, 0), "ovf", (1 / 500, 1 / 500, 1 / 30, 50000, "dslr", 0, 1, 1, 1, False),
         (0, 99, "none"), 0, "sr44", [], 0, "", (0, 1 / 30), film=("135", True), drain=0.0,
         look=look("retro_slr", "ZENIT", (200, 30, 30), "E", top=SILVER), film_extra={"modes": ["m"], "meter": "selenium"}),
    body("hasselblad_500cm", "Hasselblad 500C/M", "Hasselblad", "Hasselblad", "500C/M", "flagship", "film_medium_format", "hasselblad_v",
         F120_66, 50.0, (100, 25, 3200), 12.5, (0, 0), "ovf", (1 / 500, 1 / 500, 1, 100000, "leaf", 0, 0.8, 0.8, 1, False),
         (0, 99, "none"), 0, "sr44", [], 0, "", (0, 1 / 500), film=("120", True), drain=0.0,
         look=look("medium_box", "HASSELBLAD", (200, 200, 205), "500", top=SILVER), shutter_extra={"leaf": True}, film_extra={"modes": ["m"], "meter": "none", "iso_dial": "none"}),
    body("contax_t2", "Contax T2", "Contax", "KYOCERA", "CONTAX T2", "pro", "film_compact", "fixed", F135, 24.0,
         (100, 25, 5000), 12.0, (0, 0), "ovf", (1 / 500, 1 / 500, 8, 50000, "leaf", 0, 1.5, 1.5, 1, False),
         (0.5, -12, "none"), 0, "cr2_lithium", [], 0, "", (8, 1 / 500), film=("135", False), drain=0.5,
         fixed_lens="contax_t2_sonnar_38", look=look("compact", "CONTAX", (200, 160, 60), "T2", top=TITAN, body_c=TITAN), shutter_extra={"leaf": True}, film_extra={"modes": ["p", "a"], "iso_dial": "dx"}),
]

# ----------------------------------------------------------------------------- lenses


def lens(key, name, brand, exif, mount, circle, focal, aperture, min_ap, min_focus, motor, af_speed, ois, blades,
         vig, sharp, dist, ca, flare, filter_mm, dims, look="black", rounded=True, bokeh="smooth", tc=False,
         projection="rectilinear", tilt_shift=None, max_mag=0.0, tier="mid"):
    """focal: (min, max); aperture: (wide, tele); dist: (wide, tele); dims: (length mm, diameter mm)."""
    optics = {"blades": blades, "rounded": rounded, "vignetting_ev": vig, "sharpness": sharp,
              "distortion_wide": dist[0], "distortion_tele": dist[1], "ca_px": ca, "flare": flare, "bokeh": bokeh}
    if projection != "rectilinear":
        optics["projection"] = projection
    if tilt_shift:
        optics["tilt_shift"] = {"tilt_deg": tilt_shift[0], "shift_mm": tilt_shift[1]}
    if max_mag:
        optics["max_magnification"] = max_mag
    spec = {
        "brand": brand, "exif_lens_model": exif, "mount": rc(mount), "image_circle": circle,
        "focal_min": focal[0], "focal_max": focal[1], "aperture_wide": aperture[0], "aperture_tele": aperture[1],
        "aperture_min": min_ap, "min_focus_m": min_focus,
        "af": {"motor": motor, "speed": af_speed}, "ois_stops": ois, "optics": optics,
        "filter_mm": filter_mm, "tc_compatible": tc, "model": rc(f"item/lens/{key}"),
    }
    zoom = focal[1] > focal[0]
    label = f"{focal[0]}-{focal[1]}" if zoom else f"{focal[0]}"
    return {"key": key, "name": name, "spec": spec, "tier": tier, "zoom": zoom, "label": label,
            "length": dims[0], "diameter": dims[1], "look": look, "fixed": mount == "fixed",
            "kind": "fisheye" if projection != "rectilinear" else "tilt_shift" if tilt_shift else
            "macro" if max_mag >= 0.5 else "vintage" if motor == "none" else "zoom" if zoom else "prime"}


N = "none"
L = "linear"
ST = "stepping"
US = "fast"

LENSES = [
    # ---------------------------------------------------------------- Sony E
    lens("sony_fe_50_f18", "Sony FE 50mm F1.8", "Sony", "FE 50mm F1.8", "sony_e", "ff", (50, 50), (1.8, 1.8), 22, 0.45,
         ST, 0.7, 0, 7, 1.3, 0.92, (-0.01, -0.01), 1.5, 0.6, 49, (45, 68), tier="entry", max_mag=0.14),
    lens("sony_fe_24_70_gm2", "Sony FE 24-70mm F2.8 GM II", "Sony", "FE 24-70mm F2.8 GM II", "sony_e", "ff", (24, 70), (2.8, 2.8), 22, 0.21,
         L, 1.25, 0, 11, 1.2, 1.15, (-0.03, 0.015), 0.6, 0.3, 82, (120, 88), look="sony_gm", tier="pro", max_mag=0.32),
    lens("sony_fe_16_35_gm2", "Sony FE 16-35mm F2.8 GM II", "Sony", "FE 16-35mm F2.8 GM II", "sony_e", "ff", (16, 35), (2.8, 2.8), 22, 0.22,
         L, 1.2, 0, 11, 1.6, 1.1, (-0.06, -0.01), 0.8, 0.35, 82, (111, 88), look="sony_gm", tier="pro", max_mag=0.32),
    lens("sony_fe_70_200_gm2", "Sony FE 70-200mm F2.8 GM OSS II", "Sony", "FE 70-200mm F2.8 GM OSS II", "sony_e", "ff", (70, 200), (2.8, 2.8), 22, 0.4,
         L, 1.3, 4.5, 11, 0.9, 1.15, (0.01, 0.02), 0.5, 0.3, 77, (200, 88), look="sony_gm_white", tc=True, tier="pro", max_mag=0.3),
    lens("sony_fe_90_macro", "Sony FE 90mm F2.8 Macro G OSS", "Sony", "FE 90mm F2.8 Macro G OSS", "sony_e", "ff", (90, 90), (2.8, 2.8), 22, 0.28,
         L, 0.9, 4, 9, 0.9, 1.15, (0.005, 0.005), 0.6, 0.4, 62, (130, 79), max_mag=1.0, tier="mid"),
    # ---------------------------------------------------------------- Nikon Z
    lens("nikon_z_dx_16_50", "Nikon NIKKOR Z DX 16-50mm f/3.5-6.3 VR", "Nikon", "NIKKOR Z DX 16-50mm f/3.5-6.3 VR", "nikon_z", "apsc", (16, 50), (3.5, 6.3), 22, 0.2,
         ST, 0.85, 4.5, 7, 1.6, 0.85, (-0.08, 0.02), 1.6, 0.6, 46, (32, 70), tier="entry", max_mag=0.2),
    lens("nikon_z_24_70_f4s", "Nikon NIKKOR Z 24-70mm f/4 S", "Nikon", "NIKKOR Z 24-70mm f/4 S", "nikon_z", "ff", (24, 70), (4.0, 4.0), 22, 0.3,
         ST, 1.0, 0, 7, 1.3, 1.05, (-0.05, 0.02), 1.0, 0.45, 72, (88, 77.5), look="nikon_s", tier="mid", max_mag=0.3),
    lens("nikon_z_50_f18s", "Nikon NIKKOR Z 50mm f/1.8 S", "Nikon", "NIKKOR Z 50mm f/1.8 S", "nikon_z", "ff", (50, 50), (1.8, 1.8), 16, 0.4,
         ST, 1.0, 0, 9, 1.2, 1.15, (-0.005, -0.005), 0.7, 0.4, 62, (87, 76), look="nikon_s", tier="mid", max_mag=0.15),
    lens("nikon_z_14_24_f28s", "Nikon NIKKOR Z 14-24mm f/2.8 S", "Nikon", "NIKKOR Z 14-24mm f/2.8 S", "nikon_z", "ff", (14, 24), (2.8, 2.8), 22, 0.28,
         ST, 1.1, 0, 9, 1.8, 1.1, (-0.07, -0.02), 0.8, 0.4, 112, (125, 89), look="nikon_s", tier="pro", max_mag=0.13),
    lens("nikon_z_70_200_f28s", "Nikon NIKKOR Z 70-200mm f/2.8 VR S", "Nikon", "NIKKOR Z 70-200mm f/2.8 VR S", "nikon_z", "ff", (70, 200), (2.8, 2.8), 22, 0.5,
         ST, 1.2, 5.5, 9, 0.9, 1.15, (0.01, 0.02), 0.5, 0.3, 77, (220, 89), look="nikon_s", tc=True, tier="pro", max_mag=0.2),
    lens("nikon_z_400_f45s", "Nikon NIKKOR Z 400mm f/4.5 VR S", "Nikon", "NIKKOR Z 400mm f/4.5 VR S", "nikon_z", "ff", (400, 400), (4.5, 4.5), 32, 2.5,
         ST, 1.1, 5.5, 9, 0.8, 1.1, (0.01, 0.01), 0.5, 0.3, 95, (234, 104), look="nikon_s", tc=True, tier="pro", max_mag=0.18),
    lens("nikon_z_600_f63_pf", "Nikon NIKKOR Z 600mm f/6.3 VR S", "Nikon", "NIKKOR Z 600mm f/6.3 VR S", "nikon_z", "ff", (600, 600), (6.3, 6.3), 32, 4.0,
         ST, 1.0, 5.0, 9, 0.8, 1.05, (0.01, 0.01), 0.6, 0.45, 95, (278, 106), look="nikon_s", tc=True, tier="pro", max_mag=0.14),
    lens("nikon_z_mc_105", "Nikon NIKKOR Z MC 105mm f/2.8 VR S", "Nikon", "NIKKOR Z MC 105mm f/2.8 VR S", "nikon_z", "ff", (105, 105), (2.8, 2.8), 32, 0.29,
         ST, 0.9, 4.5, 9, 0.8, 1.15, (0.005, 0.005), 0.5, 0.35, 62, (140, 85), look="nikon_s", max_mag=1.0, tier="pro"),
    # ---------------------------------------------------------------- Nikon F
    lens("nikon_afp_dx_18_55", "Nikon AF-P DX 18-55mm f/3.5-5.6G VR", "Nikon", "AF-P DX NIKKOR 18-55mm f/3.5-5.6G VR", "nikon_f", "apsc", (18, 55), (3.5, 5.6), 22, 0.25,
         ST, 0.9, 4, 7, 1.5, 0.85, (-0.07, 0.02), 1.6, 0.6, 55, (63, 65), tier="entry", max_mag=0.38),
    lens("nikon_afs_24_120_f4", "Nikon AF-S 24-120mm f/4G ED VR", "Nikon", "AF-S NIKKOR 24-120mm f/4G ED VR", "nikon_f", "ff", (24, 120), (4.0, 4.0), 22, 0.45,
         US, 1.0, 4, 9, 1.4, 0.95, (-0.06, 0.03), 1.2, 0.5, 77, (104, 84), look="nikon_gold", tier="mid", max_mag=0.24),
    lens("nikon_ais_50_f14", "Nikon NIKKOR 50mm f/1.4 Ai-S", "Nikon", "NIKKOR 50mm f/1.4 Ai-S", "nikon_f", "ff", (50, 50), (1.4, 1.4), 16, 0.45,
         N, 0, 0, 7, 1.6, 0.88, (-0.01, -0.01), 1.8, 0.8, 52, (36, 64), look="vintage", rounded=False, tier="entry", max_mag=0.15),
    # ---------------------------------------------------------------- Canon RF
    lens("canon_rfs_18_45", "Canon RF-S 18-45mm F4.5-6.3 IS STM", "Canon", "RF-S18-45mm F4.5-6.3 IS STM", "canon_rf", "apsc", (18, 45), (4.5, 6.3), 32, 0.15,
         ST, 0.85, 4, 7, 1.6, 0.85, (-0.08, 0.02), 1.6, 0.6, 49, (45, 67), tier="entry", max_mag=0.26),
    lens("canon_rf_50_f18", "Canon RF 50mm F1.8 STM", "Canon", "RF50mm F1.8 STM", "canon_rf", "ff", (50, 50), (1.8, 1.8), 22, 0.3,
         ST, 0.7, 0, 7, 1.4, 0.92, (-0.01, -0.01), 1.4, 0.6, 43, (41, 69), tier="entry", max_mag=0.25),
    lens("canon_rf_24_105_f4l", "Canon RF 24-105mm F4 L IS USM", "Canon", "RF24-105mm F4 L IS USM", "canon_rf", "ff", (24, 105), (4.0, 4.0), 22, 0.45,
         L, 1.1, 5, 9, 1.4, 1.05, (-0.06, 0.03), 1.0, 0.45, 77, (107, 84), look="canon_l", tier="mid", max_mag=0.24),
    lens("canon_rf_15_35_f28l", "Canon RF 15-35mm F2.8 L IS USM", "Canon", "RF15-35mm F2.8 L IS USM", "canon_rf", "ff", (15, 35), (2.8, 2.8), 22, 0.28,
         L, 1.15, 5, 9, 1.7, 1.1, (-0.07, -0.01), 0.8, 0.4, 82, (127, 89), look="canon_l", tier="pro", max_mag=0.21),
    lens("canon_rf_100_500l", "Canon RF 100-500mm F4.5-7.1 L IS USM", "Canon", "RF100-500mm F4.5-7.1 L IS USM", "canon_rf", "ff", (100, 500), (4.5, 7.1), 32, 0.9,
         L, 1.15, 5, 9, 0.9, 1.1, (0.01, 0.02), 0.6, 0.35, 77, (208, 94), look="canon_l_white", tc=True, tier="pro", max_mag=0.33),
    lens("canon_rf_800_f11", "Canon RF 800mm F11 IS STM", "Canon", "RF800mm F11 IS STM", "canon_rf", "ff", (800, 800), (11.0, 11.0), 11, 6.0,
         ST, 0.8, 4, 9, 0.7, 0.95, (0.0, 0.0), 0.8, 0.5, 95, (352, 102), tc=True, tier="mid", max_mag=0.14),
    lens("canon_rf_100_macro", "Canon RF 100mm F2.8 L MACRO IS USM", "Canon", "RF100mm F2.8 L MACRO IS USM", "canon_rf", "ff", (100, 100), (2.8, 2.8), 32, 0.26,
         L, 1.1, 5, 9, 0.9, 1.15, (0.005, 0.005), 0.5, 0.35, 67, (148, 81.5), look="canon_l", max_mag=1.4, tier="pro"),
    # ---------------------------------------------------------------- Canon EF / FD
    lens("canon_efs_18_55", "Canon EF-S 18-55mm f/4-5.6 IS STM", "Canon", "EF-S18-55mm f/4-5.6 IS STM", "canon_ef", "apsc", (18, 55), (4.0, 5.6), 22, 0.25,
         ST, 0.85, 4, 7, 1.5, 0.85, (-0.07, 0.02), 1.6, 0.6, 58, (62, 66), tier="entry", max_mag=0.25),
    lens("canon_ef_24_70_f28l2", "Canon EF 24-70mm f/2.8L II USM", "Canon", "EF24-70mm f/2.8L II USM", "canon_ef", "ff", (24, 70), (2.8, 2.8), 22, 0.38,
         US, 1.1, 0, 9, 1.4, 1.1, (-0.04, 0.02), 0.8, 0.4, 82, (113, 88.5), look="canon_l", tier="pro", max_mag=0.21),
    lens("canon_ef_70_200_f28l3", "Canon EF 70-200mm f/2.8L IS III USM", "Canon", "EF70-200mm f/2.8L IS III USM", "canon_ef", "ff", (70, 200), (2.8, 2.8), 32, 1.2,
         US, 1.1, 3.5, 8, 0.9, 1.1, (0.01, 0.02), 0.6, 0.35, 77, (199, 89), look="canon_l_white", tc=True, tier="pro", max_mag=0.21),
    lens("canon_tse_24_f35l2", "Canon TS-E 24mm f/3.5L II", "Canon", "TS-E24mm f/3.5L II", "canon_ef", "ff", (24, 24), (3.5, 3.5), 22, 0.21,
         N, 0, 0, 8, 1.2, 1.1, (-0.02, -0.02), 0.8, 0.4, 82, (107, 89), look="canon_l", tilt_shift=(8.5, 12.0), tier="pro", max_mag=0.34),
    lens("canon_fd_50_f18", "Canon FD 50mm f/1.8", "Canon", "FD 50mm 1:1.8", "canon_fd", "ff", (50, 50), (1.8, 1.8), 22, 0.6,
         N, 0, 0, 6, 1.4, 0.88, (-0.01, -0.01), 1.8, 0.8, 52, (34, 63), look="vintage", rounded=False, tier="entry", max_mag=0.15),
    # ---------------------------------------------------------------- Fujifilm
    lens("fuji_xf_18_55", "Fujifilm XF 18-55mm F2.8-4 R LM OIS", "Fujifilm", "XF18-55mmF2.8-4 R LM OIS", "fuji_x", "apsc", (18, 55), (2.8, 4.0), 22, 0.3,
         L, 1.0, 4, 7, 1.3, 1.0, (-0.05, 0.02), 1.0, 0.5, 58, (70, 65), look="fuji", tier="entry", max_mag=0.15),
    lens("fuji_xf_23_f2", "Fujifilm XF 23mm F2 R WR", "Fujifilm", "XF23mmF2 R WR", "fuji_x", "apsc", (23, 23), (2.0, 2.0), 16, 0.22,
         ST, 1.0, 0, 9, 1.2, 1.0, (-0.02, -0.02), 1.0, 0.5, 43, (52, 60), look="fuji", tier="entry", max_mag=0.13),
    lens("fuji_xf_56_f12", "Fujifilm XF 56mm F1.2 R WR", "Fujifilm", "XF56mmF1.2 R WR", "fuji_x", "apsc", (56, 56), (1.2, 1.2), 16, 0.5,
         L, 1.0, 0, 11, 1.3, 1.1, (0.0, 0.0), 0.8, 0.45, 67, (76, 73), look="fuji", tier="mid", max_mag=0.14),
    lens("fuji_xf_16_55_f28", "Fujifilm XF 16-55mm F2.8 R LM WR", "Fujifilm", "XF16-55mmF2.8 R LM WR", "fuji_x", "apsc", (16, 55), (2.8, 2.8), 22, 0.3,
         L, 1.0, 0, 9, 1.4, 1.1, (-0.05, 0.02), 0.8, 0.4, 77, (106, 83), look="fuji", tier="pro", max_mag=0.16),
    lens("fuji_xf_150_600", "Fujifilm XF 150-600mm F5.6-8 R LM OIS WR", "Fujifilm", "XF150-600mmF5.6-8 R LM OIS WR", "fuji_x", "apsc", (150, 600), (5.6, 8.0), 22, 2.4,
         L, 1.0, 5, 9, 0.8, 1.05, (0.01, 0.01), 0.6, 0.4, 82, (314, 99.5), look="silver", tc=True, tier="pro", max_mag=0.24),
    lens("fuji_xf_80_macro", "Fujifilm XF 80mm F2.8 R LM OIS WR Macro", "Fujifilm", "XF80mmF2.8 R LM OIS WR Macro", "fuji_x", "apsc", (80, 80), (2.8, 2.8), 22, 0.25,
         L, 0.9, 5, 9, 0.9, 1.15, (0.005, 0.005), 0.5, 0.35, 62, (130, 80), look="fuji", max_mag=1.0, tc=True, tier="pro"),
    lens("fuji_gf_63_f28", "Fujifilm GF 63mm F2.8 R WR", "Fujifilm", "GF63mmF2.8 R WR", "fuji_g", "mf", (63, 63), (2.8, 2.8), 32, 0.5,
         L, 0.8, 0, 9, 1.0, 1.15, (-0.01, -0.01), 0.6, 0.4, 62, (71, 84), look="fuji", tier="flagship", max_mag=0.17),
    lens("fuji_gf_110_f2", "Fujifilm GF 110mm F2 R LM WR", "Fujifilm", "GF110mmF2 R LM WR", "fuji_g", "mf", (110, 110), (2.0, 2.0), 22, 0.9,
         L, 0.9, 0, 9, 1.1, 1.15, (0.0, 0.0), 0.5, 0.35, 77, (125, 94), look="fuji", tier="flagship", max_mag=0.16),
    # ---------------------------------------------------------------- Leica / Hasselblad
    lens("leica_summilux_m_35_f14", "Leica Summilux-M 35mm f/1.4 ASPH.", "Leica", "SUMMILUX-M 1:1.4/35 ASPH.", "leica_m", "ff", (35, 35), (1.4, 1.4), 16, 0.7,
         N, 0, 0, 11, 1.4, 1.1, (-0.01, -0.01), 0.8, 0.4, 46, (46, 56), look="black", tier="flagship", max_mag=0.06),
    lens("zeiss_planar_80_f28", "Carl Zeiss Planar 80mm f/2.8 CF T*", "Zeiss", "Planar 2.8/80 CF T*", "hasselblad_v", "6x6", (80, 80), (2.8, 2.8), 22, 0.9,
         N, 0, 0, 5, 1.0, 1.0, (0.0, 0.0), 0.8, 0.5, 60, (65, 75), look="chrome_black", rounded=False, tier="flagship", max_mag=0.1),
    lens("hasselblad_xcd_55_f25", "Hasselblad XCD 55mm f/2.5 V", "Hasselblad", "XCD 2,5/55V", "hasselblad_xcd", "mf", (55, 55), (2.5, 2.5), 32, 0.45,
         L, 0.9, 0, 9, 1.0, 1.15, (-0.005, -0.005), 0.6, 0.35, 72, (84, 77), look="hasselblad", tier="flagship", max_mag=0.15),
    # ---------------------------------------------------------------- L-mount / MFT
    lens("panasonic_s_20_60", "Panasonic Lumix S 20-60mm F3.5-5.6", "Panasonic", "LUMIX S 20-60/F3.5-5.6", "l_mount", "ff", (20, 60), (3.5, 5.6), 22, 0.15,
         ST, 0.85, 0, 9, 1.5, 0.9, (-0.07, 0.02), 1.4, 0.55, 67, (87, 77), tier="entry", max_mag=0.43),
    lens("panasonic_s_24_105", "Panasonic Lumix S 24-105mm F4 Macro O.I.S.", "Panasonic", "LUMIX S 24-105/F4", "l_mount", "ff", (24, 105), (4.0, 4.0), 22, 0.3,
         ST, 1.0, 5, 9, 1.4, 1.0, (-0.06, 0.03), 1.0, 0.45, 77, (118, 83), max_mag=0.5, tier="mid"),
    lens("om_mzuiko_12_40", "OM System M.Zuiko 12-40mm F2.8 PRO", "OM System", "M.ZUIKO DIGITAL ED 12-40mm F2.8 PRO", "mft", "mft", (12, 40), (2.8, 2.8), 22, 0.2,
         L, 1.05, 0, 7, 1.3, 1.1, (-0.06, 0.02), 0.9, 0.45, 62, (84, 70), look="silver_ring", tier="pro", max_mag=0.3),
    lens("om_mzuiko_8_fisheye", "OM System M.Zuiko 8mm F1.8 Fisheye PRO", "OM System", "M.ZUIKO DIGITAL ED 8mm F1.8 Fisheye PRO", "mft", "mft", (8, 8), (1.8, 1.8), 22, 0.12,
         L, 1.0, 0, 7, 0.8, 1.05, (0.0, 0.0), 1.2, 0.6, 0, (80, 62), look="silver_ring", projection="equisolid", tier="pro", max_mag=0.2),
    # ---------------------------------------------------------------- third party and vintage
    lens("sigma_35_f14_art_e", "Sigma 35mm F1.4 DG DN Art (Sony E)", "Sigma", "35mm F1.4 DG DN | Art 021", "sony_e", "ff", (35, 35), (1.4, 1.4), 16, 0.3,
         ST, 1.0, 0, 11, 1.5, 1.15, (-0.01, -0.01), 0.8, 0.4, 67, (112, 76), look="sigma", tier="mid", max_mag=0.19),
    lens("sigma_35_f14_art_l", "Sigma 35mm F1.4 DG DN Art (L-Mount)", "Sigma", "35mm F1.4 DG DN | Art 021", "l_mount", "ff", (35, 35), (1.4, 1.4), 16, 0.3,
         ST, 1.0, 0, 11, 1.5, 1.15, (-0.01, -0.01), 0.8, 0.4, 67, (112, 76), look="sigma", tier="mid", max_mag=0.19),
    lens("samyang_8_fisheye_e", "Samyang 8mm F3.5 Fish-eye CS II (Sony E)", "Samyang", "----", "sony_e", "apsc", (8, 8), (3.5, 3.5), 22, 0.3,
         N, 0, 0, 6, 1.0, 0.9, (0.0, 0.0), 1.6, 0.8, 0, (75, 75), look="black", projection="stereographic", tier="entry", max_mag=0.09),
    lens("samyang_8_fisheye_f", "Samyang 8mm F3.5 Fish-eye CS II (Nikon F)", "Samyang", "----", "nikon_f", "apsc", (8, 8), (3.5, 3.5), 22, 0.3,
         N, 0, 0, 6, 1.0, 0.9, (0.0, 0.0), 1.6, 0.8, 0, (75, 75), look="black", projection="stereographic", tier="entry", max_mag=0.09),
    lens("samyang_8_fisheye_x", "Samyang 8mm F3.5 Fish-eye CS II (Fujifilm X)", "Samyang", "----", "fuji_x", "apsc", (8, 8), (3.5, 3.5), 22, 0.3,
         N, 0, 0, 6, 1.0, 0.9, (0.0, 0.0), 1.6, 0.8, 0, (75, 75), look="black", projection="stereographic", tier="entry", max_mag=0.09),
    lens("helios_44_2", "Helios 44-2 58mm f/2", "KMZ", "----", "m42", "ff", (58, 58), (2.0, 2.0), 16, 0.5,
         N, 0, 0, 8, 1.6, 0.82, (0.0, 0.0), 2.2, 0.95, 49, (47, 63), look="chrome", rounded=False, bokeh="swirl", tier="entry", max_mag=0.13),
    lens("jupiter_9", "Jupiter-9 85mm f/2", "KMZ", "----", "m42", "ff", (85, 85), (2.0, 2.0), 22, 0.8,
         N, 0, 0, 15, 1.3, 0.88, (0.0, 0.0), 1.8, 0.85, 49, (75, 60), look="chrome", tier="entry", max_mag=0.12),
    lens("meyer_trioplan_100", "Meyer-Optik Trioplan 100mm f/2.8", "Meyer-Optik", "----", "m42", "ff", (100, 100), (2.8, 2.8), 22, 1.0,
         N, 0, 0, 15, 1.2, 0.84, (0.0, 0.0), 2.0, 0.9, 49, (75, 60), look="chrome", bokeh="bubble", tier="mid", max_mag=0.11),
    lens("pentax_smc_50_f17", "SMC Pentax-M 50mm f/1.7", "Pentax", "----", "pentax_k", "ff", (50, 50), (1.7, 1.7), 22, 0.45,
         N, 0, 0, 6, 1.4, 0.9, (-0.01, -0.01), 1.6, 0.75, 49, (31, 63), look="vintage", rounded=False, tier="entry", max_mag=0.15),
    lens("olympus_zuiko_50_f18", "Olympus OM Zuiko 50mm f/1.8", "Olympus", "----", "olympus_om", "ff", (50, 50), (1.8, 1.8), 16, 0.45,
         N, 0, 0, 6, 1.4, 0.9, (-0.01, -0.01), 1.6, 0.75, 49, (31, 60), look="vintage", rounded=False, tier="entry", max_mag=0.15),
    # ---------------------------------------------------------------- built into compact bodies (not sold separately)
    lens("fuji_x100vi_23", "Fujinon 23mm F2", "Fujifilm", "23mmF2", "fixed", "apsc", (23, 23), (2.0, 2.0), 16, 0.1,
         L, 0.9, 0, 9, 1.0, 1.0, (-0.01, -0.01), 0.9, 0.45, 49, (24, 52), look="silver", max_mag=0.2),
    lens("leica_q3_28", "Leica Summilux 28mm f/1.7 ASPH.", "Leica", "SUMMILUX 1:1.7/28 ASPH.", "fixed", "ff", (28, 28), (1.7, 1.7), 16, 0.17,
         L, 1.0, 2.0, 9, 1.4, 1.15, (-0.02, -0.02), 0.8, 0.4, 49, (40, 62), look="black", max_mag=0.28),  # Q3 has OIS
    lens("contax_t2_sonnar_38", "Carl Zeiss Sonnar 38mm f/2.8 T*", "Zeiss", "Sonnar 2.8/38 T*", "fixed", "ff", (38, 38), (2.8, 2.8), 16, 0.7,
         "slow", 0.6, 0, 6, 1.2, 1.05, (-0.01, -0.01), 0.9, 0.45, 0, (16, 40), look="titanium", max_mag=0.07),
]

# ----------------------------------------------------------------------------- adapters and teleconverters


def adapter(key, name, frm, to, af="none", af_speed=0.0, electronic=False, colour=(60, 60, 64)):
    length = round(flange(frm) - flange(to), 2)
    assert length > 0, key
    return {"key": key, "name": name, "colour": colour,
            "spec": {"name": name, "from": rc(frm), "to": rc(to), "af": af, "af_speed": af_speed,
                     "electronic": electronic, "length_mm": length, "model": rc(f"item/adapter/{key}")}}


ADAPTERS = [
    adapter("canon_ef_eos_r", "Canon Mount Adapter EF-EOS R", "canon_ef", "canon_rf", "full", 1.0, True, (30, 30, 32)),
    adapter("nikon_ftz2", "Nikon FTZ II (F → Z)", "nikon_f", "nikon_z", "slow", 0.9, True, (30, 30, 32)),
    adapter("fringer_ef_fx", "Fringer EF-FX Pro II (EF → X)", "canon_ef", "fuji_x", "slow", 0.7, True, (36, 36, 38)),
    adapter("sigma_mc21_ef_l", "Sigma MC-21 (EF → L)", "canon_ef", "l_mount", "slow", 0.8, True, (30, 30, 32)),
    adapter("fd_to_rf", "Adapter Canon FD → RF", "canon_fd", "canon_rf"),
    adapter("fd_to_e", "Adapter Canon FD → Sony E", "canon_fd", "sony_e"),
    adapter("m42_to_e", "Adapter M42 → Sony E", "m42", "sony_e", colour=(150, 152, 156)),
    adapter("m42_to_z", "Adapter M42 → Nikon Z", "m42", "nikon_z", colour=(150, 152, 156)),
    adapter("m42_to_rf", "Adapter M42 → Canon RF", "m42", "canon_rf", colour=(150, 152, 156)),
    adapter("m42_to_x", "Adapter M42 → Fujifilm X", "m42", "fuji_x", colour=(150, 152, 156)),
    adapter("m42_to_l", "Adapter M42 → L-Mount", "m42", "l_mount", colour=(150, 152, 156)),
    adapter("m42_to_mft", "Adapter M42 → Micro Four Thirds", "m42", "mft", colour=(150, 152, 156)),
    adapter("m42_to_ef", "Adapter M42 → Canon EF", "m42", "canon_ef", colour=(150, 152, 156)),
    adapter("m_to_z", "Adapter Leica M → Nikon Z", "leica_m", "nikon_z", colour=(150, 152, 156)),
    adapter("m_to_rf", "Adapter Leica M → Canon RF", "leica_m", "canon_rf", colour=(150, 152, 156)),
    adapter("m_to_x", "Adapter Leica M → Fujifilm X", "leica_m", "fuji_x", colour=(150, 152, 156)),
    adapter("m_to_l", "Leica M-Adapter L", "leica_m", "l_mount", colour=(150, 152, 156)),
    adapter("k_to_e", "Adapter Pentax K → Sony E", "pentax_k", "sony_e"),
    adapter("om_to_e", "Adapter Olympus OM → Sony E", "olympus_om", "sony_e"),
    adapter("om_to_mft", "Olympus MF-1 (OM → Micro Four Thirds)", "olympus_om", "mft"),
    adapter("v_to_g", "Adapter Hasselblad V → Fujifilm G", "hasselblad_v", "fuji_g"),
    adapter("hasselblad_xv", "Hasselblad XV Lens Adapter (V → XCD)", "hasselblad_v", "hasselblad_xcd", colour=(120, 120, 124)),
]


def tc(key, name, exif, mount, factor, length_mm, colour=(225, 225, 222)):
    return {"key": key, "name": name, "colour": colour, "length_mm": length_mm,
            "spec": {"name": name, "exif_name": exif, "mount": rc(mount), "factor": factor,
                     "af_speed": 0.85 if factor < 1.6 else 0.6, "sharpness": 0.96 if factor < 1.6 else 0.9,
                     "length_mm": length_mm, "model": rc(f"item/teleconverter/{key}")}}


TELECONVERTERS = [
    tc("nikon_z_tc14", "Nikon Z Teleconverter TC-1.4x", "TC-1.4x", "nikon_z", 1.4, 18.5, (30, 30, 32)),
    tc("nikon_z_tc20", "Nikon Z Teleconverter TC-2.0x", "TC-2.0x", "nikon_z", 2.0, 31.5, (30, 30, 32)),
    tc("canon_rf_ext14", "Canon Extender RF 1.4x", "EXTENDER RF1.4x", "canon_rf", 1.4, 22.0),
    tc("canon_rf_ext20", "Canon Extender RF 2x", "EXTENDER RF2x", "canon_rf", 2.0, 34.5),
    tc("canon_ef_ext14_3", "Canon Extender EF 1.4x III", "EXTENDER EF1.4x III", "canon_ef", 1.4, 27.2),
    tc("canon_ef_ext20_3", "Canon Extender EF 2x III", "EXTENDER EF2x III", "canon_ef", 2.0, 52.7),
    tc("fuji_xf14x", "Fujifilm XF1.4X TC WR", "XF1.4X TC WR", "fuji_x", 1.4, 15.0, (30, 30, 32)),
    tc("fuji_xf2x", "Fujifilm XF2X TC WR", "XF2X TC WR", "fuji_x", 2.0, 15.0, (30, 30, 32)),
]


# --- screw-in filters: one entry per kind, made in the listed thread sizes (key = <base>_<size>).
# (base, English name, Russian name, spec fields, icon tint, icon alpha, extra recipe ingredient (item, count), sizes)
_ALL = (67, 77, 82, 95, 112)
_SMALL = (67, 82, 95)
FILTER_KINDS = [
    ("uv", "UV", "UV", {"kind": "uv"}, "#ccd8ff", 60, ("minecraft:quartz", 1), _ALL),
    ("cpl", "Circular Polariser", "Поляризационный", {"kind": "cpl"}, "#3a4a66", 140, ("minecraft:amethyst_shard", 1), _ALL),
    ("nd8", "ND8", "ND8", {"kind": "nd", "nd_stops": 3}, "#555555", 120, ("minecraft:black_dye", 1), _ALL),
    ("nd64", "ND64", "ND64", {"kind": "nd", "nd_stops": 6}, "#333333", 170, ("minecraft:black_dye", 2), _ALL),
    ("nd1000", "ND1000", "ND1000", {"kind": "nd", "nd_stops": 10}, "#111111", 230, ("minecraft:black_dye", 4), _ALL),
    ("gnd8", "Soft GND8", "Градиентный GND8", {"kind": "gnd", "gnd_stops": 3}, "#4a4a4a", 150, ("minecraft:gray_dye", 2), _SMALL),
    ("mist4", "Black Mist 1/4", "Black Mist 1/4", {"kind": "mist", "mist": 0.25}, "#9a9aa4", 110, ("minecraft:bone_meal", 2), _SMALL),
    ("mist8", "Black Mist 1/8", "Black Mist 1/8", {"kind": "mist", "mist": 0.125}, "#b4b4bc", 90, ("minecraft:bone_meal", 1), _SMALL),
    ("star6", "Star 6x", "Звёздный 6x", {"kind": "star", "star_points": 6}, "#e8eef8", 70, ("minecraft:glowstone_dust", 1), (67, 82)),
    ("yellow", "Yellow 8", "Жёлтый 8", {"kind": "color", "r": 1, "g": 0.9, "b": 0.35}, "#ffd800", 150, ("minecraft:yellow_dye", 1), _SMALL),
    ("orange", "Orange 21", "Оранжевый 21", {"kind": "color", "r": 1, "g": 0.6, "b": 0.15}, "#ff8a00", 160, ("minecraft:orange_dye", 1), _SMALL),
    ("red", "Red 25", "Красный 25", {"kind": "color", "r": 1, "g": 0.3, "b": 0.08}, "#d01818", 170, ("minecraft:red_dye", 1), _SMALL),
    ("green", "Green 11", "Зелёный 11", {"kind": "color", "r": 0.35, "g": 1, "b": 0.3, "nd_stops": 2}, "#3aa040", 160, ("minecraft:green_dye", 1), _SMALL),
    ("blue80a", "Blue 80A", "Синий 80A", {"kind": "color", "r": 0.42, "g": 0.66, "b": 1, "nd_stops": 2}, "#3070e0", 150, ("minecraft:blue_dye", 1), (67, 82)),
    ("amber85", "Amber 85", "Янтарный 85", {"kind": "color", "r": 1, "g": 0.78, "b": 0.5, "nd_stops": 0.67}, "#e09040", 140, ("minecraft:orange_dye", 1), (67, 82)),
]

FILTERS = []
for _base, _en, _ru, _fields, _tint, _alpha, _extra, _sizes in FILTER_KINDS:
    for _size in _sizes:
        _key = f"{_base}_{_size}"
        _spec = {"name": f"{_en} {_size}mm", **_fields, "diameter_mm": _size, "model": rc(f"item/filter/{_key}")}
        FILTERS.append({"key": _key, "name": f"{_en} {_size} mm", "name_ru": f"{_ru} {_size} мм", "spec": _spec,
                        "tint": _tint, "alpha": _alpha, "extra": _extra, "size": _size})
