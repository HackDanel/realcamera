package dev.hackdanel.realcamera.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.block.DisplayBlock;
import dev.hackdanel.realcamera.block.PhotoStandBlock;
import dev.hackdanel.realcamera.block.TableFrameBlock;
import dev.hackdanel.realcamera.blockentity.ComputerBlockEntity;
import dev.hackdanel.realcamera.blockentity.DisplayBlockEntity;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.client.camera.Viewfinder;
import dev.hackdanel.realcamera.client.capture.PhotoCapture;
import dev.hackdanel.realcamera.client.editor.EditSession;
import dev.hackdanel.realcamera.client.photo.CardListings;
import dev.hackdanel.realcamera.client.screen.AlbumScreen;
import dev.hackdanel.realcamera.client.screen.ComputerScreen;
import dev.hackdanel.realcamera.client.screen.EditorScreen;
import dev.hackdanel.realcamera.component.AlbumData;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraSettings;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.item.PhotoAlbumItem;
import dev.hackdanel.realcamera.item.RCStacks;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.optics.ExposureScales;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoMeta;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import dev.hackdanel.realcamera.processing.EditSettings;
import dev.hackdanel.realcamera.processing.RawFile;
import dev.hackdanel.realcamera.registry.RCBlocks;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCRegistries;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Stage 6 end-to-end run (-Drealcamera.autotest=stage6): the full catalog, a showcase wall of cameras and
 * lenses, fisheye, tilt-shift and macro shots, Helios swirl and Trioplan bubbles at night, a built-in lens,
 * 120 film in a Hasselblad, structure loot.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage6Autotest {
    private static final boolean ENABLED = "stage6".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin, computer;
    private static UUID cardA, cardB;
    private static EditSession session;
    private static String editStatus = "";

    private Stage6Autotest() {}

    static void log(String msg, Object... args) {
        RealCamera.LOGGER.info("[autotest] " + msg, args);
    }

    private static void step(String label, Runnable r, int waitTicks) {
        steps.add(new Step(label, r, waitTicks, null, 0));
    }

    private static void until(String label, BooleanSupplier cond, int timeout) {
        steps.add(new Step(label, () -> {}, 0, cond, timeout));
    }

    private static void check(String label, BooleanSupplier cond, int timeout) {
        steps.add(new Step(label, () -> {}, 0, () -> {
            if (cond.getAsBoolean()) {
                log("PASS {}", label);
                return true;
            }
            return false;
        }, timeout));
    }

    private static void server(Consumer<ServerPlayer> action) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayers().get(0)));
    }

    private static void cmd(ServerPlayer p, String command) {
        p.server.getCommands().performPrefixedCommand(p.createCommandSourceStack().withPermission(4).withSuppressedOutput(), command);
    }

    private static <T> ResourceKey<T> key(ResourceKey<net.minecraft.core.Registry<T>> reg, String id) {
        return ResourceKey.create(reg, RealCamera.id(id));
    }

    private static void screenshot(String name) {
        step("screenshot " + name, () -> pendingScreenshot = name, 3);
    }

    private static void use(BlockPos pos, int slot) {
        Minecraft mc = Minecraft.getInstance();
        mc.player.getInventory().selected = slot;
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.NORTH, pos, false));
    }

    private static List<PhotoMeta> listing(UUID card) {
        List<PhotoMeta> l = card == null ? null : CardListings.get(card);
        return l == null ? List.of() : l;
    }

    private static File newestIn(File dir, String suffix) {
        File best = null;
        File[] days = dir.listFiles();
        if (days == null) return null;
        for (File d : days) {
            File[] f = d.listFiles((x, n) -> n.endsWith(suffix));
            if (f == null) continue;
            for (File file : f) {
                if (best == null || file.lastModified() > best.lastModified()) best = file;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ the script

    private static ItemStack kit(String body, String lens, String adapter, CameraSettings settings) {
        Minecraft mc = Minecraft.getInstance();
        var regs = mc.level.registryAccess();
        var ref = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(key(RCRegistries.CAMERA_BODY, body));
        ItemStack cam = RCStacks.kit(regs, ref).orElseThrow();
        var parts = CameraHelper.parts(cam);
        if (lens != null) {
            parts = parts.with(CameraParts.SLOT_LENS, RCStacks.lens(key(RCRegistries.LENS, lens)));
        }
        if (adapter != null) {
            parts = parts.with(CameraParts.SLOT_ADAPTER, RCStacks.adapter(key(RCRegistries.MOUNT_ADAPTER, adapter)));
        }
        CameraHelper.setParts(cam, parts);
        cam.set(RCComponents.CAMERA_SETTINGS.get(), settings);
        return cam;
    }

    private static CameraSettings aperture(double f, float focus) {
        return CameraSettings.DEFAULT.withMode(CameraSettings.MODE_A).withApertureIndex(ExposureScales.nearestAperture(f))
                .withFocusMode(CameraSettings.FOCUS_MF).withFocusDistance(focus);
    }

    private static void give(int slot, java.util.function.Supplier<ItemStack> stack) {
        step("give " + slot, () -> {
            Minecraft mc = Minecraft.getInstance();
            ItemStack s = stack.get();
            mc.player.getInventory().setItem(slot, s);
            mc.gameMode.handleCreativeModeItemAdd(s, 36 + slot);
        }, 10);
    }

    private static int photosBefore;

    /** Raise the camera in slot 0, optionally screenshot the viewfinder, take one picture, lower it. */
    private static void shoot(String label, String hudShot) {
        step("raise " + label, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }, 15);
        until("viewfinder " + label, Viewfinder::isActive, 60);
        step("settle " + label, () -> {}, 30);
        if (hudShot != null) {
            screenshot(hudShot);
        }
        step("shoot " + label, () -> {
            photosBefore = exported();
            Viewfinder.debugPressShutter();
        }, 5);
        until("started " + label, PhotoCapture::busy, 80);
        until("stored " + label, () -> !PhotoCapture.busy(), 1200);
        step("lower " + label, () -> Minecraft.getInstance().options.keyUse.setDown(false), 20);
    }

    private static int exported() {
        File dir = new File(Minecraft.getInstance().gameDirectory, "screenshots/realcamera");
        int n = 0;
        File[] days = dir.listFiles();
        if (days == null) return 0;
        for (File d : days) {
            File[] f = d.listFiles((x, name) -> name.endsWith(".JPG"));
            if (f != null) n += f.length;
        }
        return n;
    }

    private static void exportedCheck(String label) {
        check(label, () -> exported() > photosBefore, 200);
    }

    private static void showcase(ServerPlayer p) {
        // a wall of item frames 4 blocks west of the start, facing east
        var level = p.serverLevel();
        var regs = level.registryAccess();
        BlockPos wall = origin.offset(-4, 0, 0);
        cmd(p, String.format("fill %d %d %d %d %d %d minecraft:smooth_stone", wall.getX(), wall.getY(), wall.getZ() - 1,
                wall.getX(), wall.getY() + 3, wall.getZ() + 6));
        String[][] grid = {
                {"lens:canon_rf_100_500l", "lens:sony_fe_70_200_gm2", "lens:nikon_z_600_f63_pf", "lens:canon_rf_800_f11"},
                {"lens:canon_tse_24_f35l2", "lens:om_mzuiko_8_fisheye", "lens:helios_44_2", "lens:zeiss_planar_80_f28"},
                {"body:nikon_z9", "body:fuji_x100vi", "body:hasselblad_500cm", "body:canon_ae1p"},
                {"body:sony_a1ii", "body:fuji_gfx100ii", "body:contax_t2", "body:nikon_zf"}};
        for (int row = 0; row < grid.length; row++) {
            for (int col = 0; col < grid[row].length; col++) {
                String[] e = grid[row][col].split(":");
                ItemStack stack;
                if (e[0].equals("lens")) {
                    stack = RCStacks.lens(key(RCRegistries.LENS, e[1]));
                } else {
                    var ref = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).getOrThrow(key(RCRegistries.CAMERA_BODY, e[1]));
                    stack = RCStacks.kit(regs, ref).orElse(RCStacks.body(ref.key()));
                }
                BlockPos at = wall.offset(1, 3 - row, col + 1);
                var frame = new net.minecraft.world.entity.decoration.ItemFrame(level, at, Direction.EAST);
                frame.setItem(stack);
                level.addFreshEntity(frame);
            }
        }
    }

    /** Distance from the eye to the front of the villager's box, what the depth map sees (manual focus on it). */
    private static float villagerDistance() {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getLookAngle().scale(30));
        float best = 3.25f;
        for (var e : mc.level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class, mc.player.getBoundingBox().inflate(20))) {
            var hit = e.getBoundingBox().clip(eye, end);
            if (hit.isPresent()) best = (float) eye.distanceTo(hit.get());
        }
        return best;
    }

    private static void script() {
        step("scene", () -> server(p -> {
            origin = p.blockPosition();
            // stand in the middle of the block first: every "~" below is relative to this spot
            cmd(p, String.format("tp @s %d.5 %d %d.5 0 0", origin.getX(), origin.getY(), origin.getZ()));
            cmd(p, "time set 6000");
            cmd(p, "weather clear");
            cmd(p, "fill ~-10 ~-1 ~-6 ~12 ~-1 ~40 minecraft:grass_block");
            cmd(p, "fill ~-10 ~ ~-6 ~12 ~20 ~40 minecraft:air");
            // a tall building for the tilt-shift lens
            cmd(p, "fill ~-3 ~ ~16 ~3 ~14 ~18 minecraft:white_concrete");
            cmd(p, "fill ~-2 ~2 ~15 ~-2 ~12 ~15 minecraft:light_blue_stained_glass");
            cmd(p, "fill ~2 ~2 ~15 ~2 ~12 ~15 minecraft:light_blue_stained_glass");
            cmd(p, "fill ~-6 ~ ~8 ~-6 ~3 ~8 minecraft:red_wool");
            cmd(p, "fill ~5 ~ ~10 ~5 ~4 ~10 minecraft:yellow_wool");
            cmd(p, "summon minecraft:villager ~ ~ ~4 {NoAI:1b,Rotation:[180f,0f]}");
            // a sparse wall of lights far behind for night bokeh, and a lantern by the villager
            for (int i = -10; i <= 10; i += 2) {
                for (int y = 1; y <= 9; y += 2) {
                    cmd(p, String.format("setblock ~%d ~%d ~%d minecraft:lantern", i, y + Math.floorMod(i, 3) - 1, 30 + Math.floorMod(i * 5 + y, 5)));
                }
            }
            cmd(p, "setblock ~1 ~ ~3 minecraft:lantern");
            showcase(p);
        }), 80);

        // 1) the catalog
        step("catalog", () -> {
            var regs = Minecraft.getInstance().level.registryAccess();
            long bodies = regs.lookupOrThrow(RCRegistries.CAMERA_BODY).listElements().count();
            long lenses = regs.lookupOrThrow(RCRegistries.LENS).listElements().count();
            log(bodies >= 30 && lenses >= 50 ? "PASS catalog {} bodies, {} lenses" : "FAIL catalog {} bodies, {} lenses", bodies, lenses);
        }, 1);

        // 2) showcase wall: round lenses and the new body styles
        step("look at wall", () -> server(p -> p.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 3.0, 90f, 8f)), 40);
        screenshot("s6_01_showcase");
        step("closer", () -> server(p -> p.connection.teleport(origin.getX() - 1.2, origin.getY(), origin.getZ() + 2.0, 90f, 25f)), 40);
        screenshot("s6_02_showcase_lenses");
        step("back", () -> server(p -> p.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0f, 0f)), 20);

        // 3) in hand: Sony α1 II with the white 70-200 GM
        give(0, () -> kit("sony_a1ii", "sony_fe_70_200_gm2", null, aperture(2.8, 4f)));
        step("hold", () -> Minecraft.getInstance().player.getInventory().selected = 0, 20);
        screenshot("s6_03_in_hand");

        // 4) fisheye on Micro Four Thirds
        give(0, () -> kit("om_om1ii", "om_mzuiko_8_fisheye", null, aperture(5.6, 2f)));
        shoot("fisheye", "s6_04_fisheye_view");
        exportedCheck("fisheye photo exported");

        // 5) tilt-shift: reverse tilt for the miniature look, shift up to keep the building's verticals
        give(0, () -> kit("canon_eos_5d4", "canon_tse_24_f35l2", null,
                aperture(3.5, 12f).withTilt(-6f).withShift(10f).withSelectedParam(CameraSettings.PARAM_TILT)));
        shoot("tilt-shift", "s6_05_tilt_shift_view");
        exportedCheck("tilt-shift photo exported");
        give(0, () -> kit("canon_eos_5d4", "canon_tse_24_f35l2", null, aperture(8, 20f).withShift(12f)));
        shoot("shift", null);
        exportedCheck("shift photo exported");
        give(0, () -> kit("canon_eos_5d4", "canon_tse_24_f35l2", null, aperture(8, 20f)));
        shoot("no shift", null);

        // 6) macro: a close-up of a bee (at 1:1 the frame would be 36 mm wide: less than one texture pixel of it)
        step("bee", () -> server(p -> cmd(p, String.format(java.util.Locale.ROOT,
                "summon minecraft:bee %.2f %.2f %.2f {NoAI:1b,NoGravity:1b,Silent:1b,Rotation:[180f,0f],Tags:[\"macro\"]}",
                origin.getX() + 0.5, origin.getY() + 1.32, origin.getZ() + 1.85))), 20);
        step("face bee", () -> server(p -> p.connection.teleport(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5, 0f, 0f)), 20);
        give(0, () -> kit("sony_a7rv", "sony_fe_90_macro", null, aperture(8, 1.0f).withFocusMode(CameraSettings.FOCUS_AFS)));
        step("raise macro", () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.getInventory().selected = 0;
        }, 5);
        shoot("macro", "s6_06_macro_view");
        check("macro reaches 1:1", () -> dev.hackdanel.realcamera.optics.Optics.magnification(90, 0.28) >= 0.99
                && Minecraft.getInstance().level.registryAccess().lookupOrThrow(RCRegistries.LENS)
                .getOrThrow(key(RCRegistries.LENS, "sony_fe_90_macro")).value().maxMagnification() >= 0.99, 1);
        exportedCheck("macro photo exported");
        step("bee away", () -> server(p -> cmd(p, "kill @e[type=minecraft:bee,tag=macro]")), 20);

        // 7) built-in lens: the X100VI gets its 23 mm by itself and it cannot be taken out
        give(0, () -> RCStacks.body(key(RCRegistries.CAMERA_BODY, "fuji_x100vi")));
        step("drop to server", () -> {}, 40);
        check("built-in lens appears", () -> {
            ItemStack lens = CameraHelper.parts(Minecraft.getInstance().player.getInventory().getItem(0)).lens();
            var k = lens.get(RCComponents.LENS.get());
            return k != null && k.location().getPath().equals("fuji_x100vi_23");
        }, 100);
        step("locked slot", () -> server(p -> {
            var menu = new dev.hackdanel.realcamera.menu.CameraPartsMenu(0, p.getInventory(), InteractionHand.MAIN_HAND);
            boolean locked = !menu.getSlot(CameraParts.SLOT_LENS).mayPickup(p)
                    && !menu.accepts(CameraParts.SLOT_LENS, RCStacks.lens(key(RCRegistries.LENS, "sony_fe_85_f18")));
            log(locked ? "PASS built-in lens slot locked" : "FAIL built-in lens can be removed");
        }), 10);
        give(0, () -> kit("fuji_x100vi", null, null, aperture(4, 4f)));
        shoot("x100vi", null);
        exportedCheck("X100VI photo exported");

        // 8) 120 film in a Hasselblad 500C/M: 12 square frames per roll
        give(0, () -> kit("hasselblad_500cm", null, null, aperture(8, 4f)));
        check("120 roll loaded", () -> {
            var roll = CameraHelper.parts(Minecraft.getInstance().player.getInventory().getItem(0)).card().get(RCComponents.FILM.get());
            return roll != null && roll.format().equals("120");
        }, 40);
        shoot("hasselblad", "s6_07_hasselblad_view");
        check("one 6x6 frame on the roll", () -> {
            var roll = CameraHelper.parts(Minecraft.getInstance().player.getInventory().getItem(0)).card().get(RCComponents.FILM.get());
            return roll != null && roll.shot() == 1;
        }, 100);

        // 9) structure loot
        step("loot", () -> server(p -> {
            var level = p.serverLevel();
            int camera = 0, broken = 0;
            for (String table : new String[]{"chests/village/village_cartographer", "chests/shipwreck_supply"}) {
                var lt = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(
                        net.minecraft.core.registries.Registries.LOOT_TABLE, net.minecraft.resources.ResourceLocation.withDefaultNamespace(table)));
                for (int i = 0; i < 300; i++) {
                    var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                            .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, Vec3.atCenterOf(origin))
                            .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
                    for (ItemStack s : lt.getRandomItems(params)) {
                        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals(RealCamera.MODID)) {
                            camera++;
                            if (CameraHelper.wear(s).dead()) broken++;
                        }
                    }
                }
            }
            log(camera > 0 ? "PASS structure loot: {} camera items, {} broken bodies" : "FAIL no camera loot ({} / {})", camera, broken);
            log(broken > 0 ? "PASS shipwrecks hold broken cameras" : "FAIL no broken cameras in shipwrecks");
        }), 20);

        // 10) night: Helios swirl and Trioplan bubbles behind the villager
        step("dusk", () -> server(p -> {
            cmd(p, "time set 13200");
            cmd(p, String.format("fill %d %d %d %d %d %d minecraft:air", origin.getX() - 3, origin.getY(), origin.getZ() + 15,
                    origin.getX() + 3, origin.getY() + 14, origin.getZ() + 18));
        }), 40);
        give(0, () -> kit("sony_a7iv", "helios_44_2", "m42_to_e", aperture(2, villagerDistance())));
        shoot("helios", "s6_08_helios_view");
        exportedCheck("Helios photo exported");
        give(0, () -> kit("sony_a7iv", "meyer_trioplan_100", "m42_to_e", aperture(2.8, villagerDistance())));
        shoot("trioplan", null);
        exportedCheck("Trioplan photo exported");
        give(0, () -> kit("sony_a7iv", "sony_fe_85_f18", null, aperture(1.8, villagerDistance())));
        shoot("smooth 85", null);
        step("done", () -> {
            log("done, stopping");
            Minecraft.getInstance().stop();
        }, 60);
    }

    // ------------------------------------------------------------------ driver

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_stage6_" + System.currentTimeMillis();
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            mc.createWorldOpenFlows().createFreshLevel(name, new LevelSettings(name, GameType.CREATIVE, false,
                            Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                    new WorldOptions(20261105L, false, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
            return;
        }
        if (mc.level == null || mc.player == null || (mc.screen != null && !scripted)) return;
        if (!scripted) {
            if (++polled < 200) return;
            scripted = true;
            polled = 0;
            script();
        }
        if (wait > 0) {
            wait--;
            return;
        }
        Step s = steps.peekFirst();
        if (s == null) return;
        if (s.until() != null) {
            boolean ok;
            try {
                ok = s.until().getAsBoolean();
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) {
                steps.pollFirst();
                polled = 0;
            } else if (++polled > s.timeout()) {
                log("FAIL timeout waiting for {}", s.label());
                steps.pollFirst();
                polled = 0;
            }
            return;
        }
        steps.pollFirst();
        try {
            s.action().run();
        } catch (RuntimeException e) {
            log("FAIL step {} threw {}", s.label(), e.toString());
        }
        wait = s.delay();
    }

    @SubscribeEvent
    static void onFramePost(RenderFrameEvent.Post event) {
        if (!ENABLED || pendingScreenshot == null) return;
        String name = pendingScreenshot;
        pendingScreenshot = null;
        Minecraft mc = Minecraft.getInstance();
        NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        File dir = new File(mc.gameDirectory, "screenshots/autotest");
        Util.ioPool().execute(() -> {
            try (img) {
                dir.mkdirs();
                img.writeToFile(new File(dir, name + ".png"));
                log("screenshot {}", name);
            } catch (IOException e) {
                RealCamera.LOGGER.error("screenshot failed", e);
            }
        });
    }
}
