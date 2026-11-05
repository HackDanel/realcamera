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
import dev.hackdanel.realcamera.client.screen.JournalScreen;
import dev.hackdanel.realcamera.component.PrintData;
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
 * Stage 8 run (-Drealcamera.autotest=stage8 -Drealcamera.viewer=jei|emi|rei): the recipe viewer plugin shows the
 * photo workshop, film development and enlarger categories with their recipes.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class Stage8Autotest {
    private static final boolean ENABLED = "stage8".equals(System.getProperty("realcamera.autotest"));

    private record Step(String label, Runnable action, int delay, BooleanSupplier until, int timeout) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static boolean worldRequested, scripted;
    private static int wait, polled;
    private static String pendingScreenshot;
    private static BlockPos origin, computer;
    private static UUID cardA, cardB;
    private static EditSession session;
    private static String editStatus = "";

    private Stage8Autotest() {}

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

    private static final String VIEWER = System.getProperty("realcamera.viewer", "");

    private static long count(net.minecraft.resources.ResourceLocation id) {
        return switch (VIEWER) {
            case "jei" -> dev.hackdanel.realcamera.client.compat.JeiPlugin.count(id);
            case "emi" -> dev.hackdanel.realcamera.client.compat.EmiPlugin.count(id);
            case "rei" -> dev.hackdanel.realcamera.client.compat.ReiPlugin.count(id);
            default -> -1;
        };
    }

    private static void open(net.minecraft.resources.ResourceLocation id) {
        switch (VIEWER) {
            case "jei" -> dev.hackdanel.realcamera.client.compat.JeiPlugin.open(id);
            case "emi" -> dev.hackdanel.realcamera.client.compat.EmiPlugin.open(id);
            case "rei" -> dev.hackdanel.realcamera.client.compat.ReiPlugin.open(id);
            default -> {}
        }
    }

    private static void script() {
        step("viewer", () -> log(net.neoforged.fml.ModList.get().isLoaded(VIEWER.equals("rei") ? "roughlyenoughitems" : VIEWER) ? "PASS recipe viewer {} loaded"
                : "FAIL recipe viewer '{}' not loaded", VIEWER), 100);
        for (var id : dev.hackdanel.realcamera.client.compat.RecipeViews.CATEGORIES) {
            int expected = id.equals(dev.hackdanel.realcamera.client.compat.RecipeViews.ASSEMBLY) ? 250 : 3;
            check(VIEWER + " lists " + id.getPath() + " recipes", () -> {
                long n = count(id);
                if (n >= expected || polled % 50 == 0) log("{} recipes in {}", n, id.getPath());
                return n >= expected;
            }, 200);
            step("open " + id.getPath(), () -> open(id), 60);
            screenshot("s8_" + VIEWER + "_" + id.getPath());
            step("close " + id.getPath(), () -> Minecraft.getInstance().setScreen(null), 20);
        }
        step("done", () -> {
            log("done, stopping");
            Minecraft.getInstance().stop();
        }, 40);
    }

    // ------------------------------------------------------------------ driver

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_stage8_" + System.currentTimeMillis();
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
