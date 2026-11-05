#!/usr/bin/env python3
"""
The RealCamera advancement tab (plan §7.3): data/realcamera/advancement/*.json plus en/ru titles and descriptions
merged into the lang files. Run from the project root: python3 tools/gen_advancements.py

Triggers: realcamera:photo_taken (conditions: entity, min_stars, phenomenon, biome, dimension, flag, shot{...})
and realcamera:event (event name + min count: prints, frames_hung, film_developed, edits, commissions,
commission_tier, shutter_count, journal_creatures, journal_biomes, journal_phenomena).
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "src/main/resources/data/realcamera/advancement")
LANG = os.path.join(ROOT, "src/main/resources/assets/realcamera/lang")
NS = "realcamera"


def photo(**c):
    shot = {k: c.pop(k) for k in list(c) if k in ("min_shutter", "min_distance", "max_distance", "max_aperture",
                                                  "min_magnification", "min_sky")}
    if shot:
        c["shot"] = shot
    return {"trigger": f"{NS}:photo_taken", "conditions": c}


def event(name, n=1):
    return {"trigger": f"{NS}:event", "conditions": {"event": name, "min": n}}


# id, parent, icon, frame, criterion, (en title, en description), (ru title, ru description)
ADV = [
    ("root", None, "realcamera:camera_body", "task", photo(),
     ("RealCamera", "Take your first photo"), ("RealCamera", "Сделайте первый снимок")),
    ("first_print", "root", "realcamera:photo_print", "task", event("prints"),
     ("Hard Copy", "Print a photo"), ("На бумаге", "Напечатайте снимок")),
    ("framed", "first_print", "realcamera:photo_frame", "task", event("frames_hung"),
     ("On the Wall", "Put a print into a frame or onto a stand"), ("На стене", "Вставьте отпечаток в рамку или на стенд")),
    ("exhibition", "framed", "minecraft:item_frame", "goal", event("frames_hung", 10),
     ("Exhibition", "Hang 10 prints"), ("Выставка", "Повесьте 10 отпечатков")),
    ("newspaper", "first_print", "realcamera:editorial_desk", "task", event("commissions"),
     ("Published", "Sell a photo to a newspaper editor"), ("В номер!", "Продайте снимок редактору газеты")),
    ("scoop", "newspaper", "minecraft:emerald_block", "challenge", event("commission_tier", 5),
     ("Scoop", "Fulfil a master editor's order"), ("Сенсация", "Выполните заказ редактора-мастера")),
    ("three_stars", "root", "minecraft:sunflower", "task", photo(min_stars=3),
     ("Three Stars", "Take a 3-star photo"), ("Три звезды", "Сделайте снимок на 3 звезды")),
    ("portrait", "three_stars", "minecraft:player_head", "goal", photo(entity="minecraft:villager", min_stars=3, max_aperture=2.0),
     ("Portraitist", "A 3-star villager portrait at f/2 or wider"), ("Портретист", "Портрет жителя на 3 звезды при f/2 или светлее")),
    ("warden", "three_stars", "minecraft:sculk_shrieker", "challenge",
     photo(entity="minecraft:warden", min_stars=3, flag="electronic"),
     ("Shh, Warden", "A 3-star photo of the Warden with the silent electronic shutter"),
     ("Тихо, Варден", "Снимите Вардена на 3 звезды бесшумным электронным затвором")),
    ("telephoto", "root", "minecraft:spyglass", "goal", photo(min_distance=150),
     ("Long Lens", "A sharp subject more than 150 blocks away"), ("Длиннофокусник", "Резкий объект дальше 150 блоков")),
    ("creeper", "root", "minecraft:creeper_head", "task", photo(entity="minecraft:creeper", max_distance=3),
     ("Too Close", "Photograph a creeper less than 3 blocks away"), ("Опасная близость", "Снимите крипера ближе 3 блоков")),
    ("panda", "root", "minecraft:bamboo", "task", photo(entity="minecraft:panda", biome="minecraft:bamboo_jungle"),
     ("Wildlife", "A panda in a bamboo jungle"), ("Дикая природа", "Панда в бамбуковых джунглях")),
    ("macro_bee", "root", "minecraft:bee_nest", "goal", photo(entity="minecraft:bee", min_magnification=0.5),
     ("Eye to Eye", "A bee with a macro lens at 1:2 or closer"), ("Глаза в глаза", "Пчела макрообъективом с увеличением от 1:2")),
    ("tripod", "root", "realcamera:tripod", "task", photo(flag="tripod"),
     ("Steady", "Take a photo from a tripod"), ("Без дрожи", "Сделайте снимок со штатива")),
    ("star_trails", "tripod", "minecraft:clock", "challenge", photo(phenomenon="star_trails"),
     ("Star Trails", "A night sky exposed for 15 s or more from a tripod"),
     ("Звёздный путь", "Ночное небо со штатива с выдержкой от 15 секунд")),
    ("starry_night", "tripod", "minecraft:glow_berries", "task", photo(phenomenon="starry_night"),
     ("Starry Night", "Photograph a clear night sky"), ("Звёздная ночь", "Снимите ясное ночное небо")),
    ("sunset", "root", "minecraft:orange_dye", "task", photo(phenomenon="sunset"),
     ("Golden Hour", "Photograph a sunset"), ("Золотой час", "Снимите закат")),
    ("full_moon", "sunset", "minecraft:snowball", "task", photo(phenomenon="full_moon"),
     ("Full Moon", "The full moon in the frame"), ("Полнолуние", "Полная луна в кадре")),
    ("lightning", "sunset", "minecraft:lightning_rod", "challenge", photo(phenomenon="lightning"),
     ("Moment Catcher", "Photograph a lightning strike"), ("Ловец моментов", "Снимите удар молнии")),
    ("underwater", "root", "minecraft:heart_of_the_sea", "task", photo(flag="underwater"),
     ("Splash Housing", "Take a photo under water"), ("Подводный мир", "Сделайте снимок под водой")),
    ("flash", "root", "minecraft:glowstone_dust", "task", photo(flag="flash"),
     ("Pop!", "Take a photo with a flash"), ("Вспышка!", "Сделайте снимок со вспышкой")),
    ("fisheye", "root", "minecraft:spider_eye", "task", photo(flag="fisheye"),
     ("Fish Eye", "Take a photo with a fisheye lens"), ("Рыбий глаз", "Снимите фишаем")),
    ("tilt_shift", "fisheye", "minecraft:piston", "goal", photo(flag="tilt_shift"),
     ("Toy Town", "Take a photo with a tilted or shifted lens"), ("Игрушечный город", "Снимите со сдвигом или наклоном объектива")),
    ("vintage", "fisheye", "minecraft:iron_ingot", "task", photo(flag="adapter"),
     ("Old Glass", "Take a photo with an adapted lens"), ("Старое стекло", "Снимите объективом через адаптер")),
    ("darkroom", "root", "realcamera:film_roll", "task", event("film_developed"),
     ("Darkroom", "Develop a roll of film"), ("Тёмная комната", "Проявите плёнку")),
    ("medium_format", "darkroom", "minecraft:map", "goal", photo(flag="medium_format"),
     ("Big Negative", "Take a photo on medium format"), ("Большой кадр", "Снимите на средний формат")),
    ("editor", "root", "realcamera:computer", "task", event("edits"),
     ("Retoucher", "Save an edited photo on the computer"), ("Ретушёр", "Сохраните обработанный снимок на компьютере")),
    ("mechanical", "root", "minecraft:clock", "challenge", event("shutter_count", 100000),
     ("Mechanical", "100,000 shutter actuations on one camera"), ("Механический", "100 000 срабатываний затвора одной камеры")),
    ("nether", "root", "minecraft:netherrack", "task", photo(dimension="minecraft:the_nether"),
     ("Hot Shots", "Take a photo in the Nether"), ("Горячие кадры", "Сделайте снимок в Незере")),
    ("ghast", "nether", "minecraft:ghast_tear", "goal", photo(entity="minecraft:ghast", min_stars=2),
     ("Floating Fury", "A 2-star photo of a ghast"), ("Летающий ужас", "Снимок гаста на 2 звезды")),
    ("end_sky", "nether", "minecraft:end_stone", "task", photo(phenomenon="end_sky"),
     ("Other Sky", "Photograph the sky of the End"), ("Чужое небо", "Снимите небо Энда")),
    ("dragon", "end_sky", "minecraft:dragon_head", "challenge", photo(entity="minecraft:ender_dragon"),
     ("Dragon Hunter", "Photograph the Ender Dragon"), ("Охотник за драконом", "Снимите Эндер-дракона")),
    ("creatures_10", "root", "realcamera:field_journal", "task", event("journal_creatures", 10),
     ("Field Notes", "10 creatures in the encyclopedia"), ("Полевые заметки", "10 существ в энциклопедии")),
    ("creatures_30", "creatures_10", "minecraft:book", "challenge", event("journal_creatures", 30),
     ("Naturalist", "30 creatures in the encyclopedia"), ("Натуралист", "30 существ в энциклопедии")),
    ("biomes_10", "creatures_10", "minecraft:grass_block", "task", event("journal_biomes", 10),
     ("Traveller", "10 biomes in the encyclopedia"), ("Путешественник", "10 биомов в энциклопедии")),
    ("biomes_40", "biomes_10", "minecraft:filled_map", "challenge", event("journal_biomes", 40),
     ("The Whole World in the Lens", "40 biomes in the encyclopedia"), ("Весь мир в объективе", "40 биомов в энциклопедии")),
    ("phenomena_8", "creatures_10", "minecraft:recovery_compass", "challenge", event("journal_phenomena", 8),
     ("Weather Watcher", "8 natural phenomena in the encyclopedia"), ("Наблюдатель", "8 природных явлений в энциклопедии")),
]


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    names = {"en_us": {}, "ru_ru": {}}
    for aid, parent, icon, frame, crit, en, ru in ADV:
        display = {
            "icon": {"id": icon},
            "title": {"translate": f"advancements.{NS}.{aid}.title"},
            "description": {"translate": f"advancements.{NS}.{aid}.description"},
            "frame": frame,
            "show_toast": True,
            "announce_to_chat": True,
            "hidden": False,
        }
        adv = {"display": display, "criteria": {"done": crit}}
        if parent:
            adv["parent"] = f"{NS}:{parent}"
        else:
            display["background"] = "minecraft:textures/block/black_concrete.png"
        if frame == "challenge":
            adv["rewards"] = {"experience": 100}
        with open(os.path.join(OUT, aid + ".json"), "w", encoding="utf-8") as f:
            json.dump(adv, f, indent=2, ensure_ascii=False)
            f.write("\n")
        for lang, (title, desc) in (("en_us", en), ("ru_ru", ru)):
            names[lang][f"advancements.{NS}.{aid}.title"] = title
            names[lang][f"advancements.{NS}.{aid}.description"] = desc
    for lang, entries in names.items():
        path = os.path.join(LANG, lang + ".json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        data.update(entries)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print(len(ADV), "advancements written")


if __name__ == "__main__":
    main()
