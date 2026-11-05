package dev.hackdanel.realcamera.client.capture;

import dev.hackdanel.realcamera.RealCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only automation, enabled with -Drealcamera.autotest=spike: creates a fresh world, waits for
 * chunks, takes a window-res and a hi-res capture, then quits. Lets the capture path be checked
 * without anyone at the keyboard.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class SpikeAutotest {
    private static final boolean ENABLED = "spike".equals(System.getProperty("realcamera.autotest"));
    private static final long SEED = 20261105L;

    private static boolean worldRequested;
    private static int ticksInWorld;
    private static int step;

    private SpikeAutotest() {}

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!worldRequested && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
            worldRequested = true;
            String name = "rc_autotest_" + System.currentTimeMillis();
            GameRules rules = new GameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
            LevelSettings settings = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                    rules, WorldDataConfiguration.DEFAULT);
            RealCamera.LOGGER.info("[autotest] creating world {}", name);
            mc.createWorldOpenFlows().createFreshLevel(name, settings, new WorldOptions(SEED, true, false),
                    WorldPresets::createNormalWorldDimensions, new TitleScreen());
            return;
        }
        if (mc.level == null || mc.player == null || mc.screen != null) {
            return;
        }
        ticksInWorld++;
        switch (step) {
            case 0 -> {
                if (ticksInWorld == 20) {
                    mc.player.connection.sendCommand("time set 6000");
                    mc.player.setXRot(10f);
                }
                if (ticksInWorld >= 300) {
                    CaptureSpike.request("auto_window", 0);
                    step++;
                }
            }
            case 1 -> {
                if (!CaptureSpike.isBusy()) {
                    CaptureSpike.request("auto_hires", CaptureSpike.HIRES_LONG_SIDE);
                    step++;
                }
            }
            case 2 -> {
                if (!CaptureSpike.isBusy()) {
                    AccumulationSpike.start(2.0);
                    step++;
                }
            }
            case 3 -> {
                // Slow pan during the exposure -> visible motion blur.
                mc.player.setYRot(mc.player.getYRot() + 0.75f);
                if (!AccumulationSpike.isBusy()) {
                    step++;
                    ticksInWorld = 0;
                }
            }
            case 4 -> {
                // Let the async PNG writes finish, then quit.
                if (ticksInWorld >= 60) {
                    RealCamera.LOGGER.info("[autotest] done, stopping");
                    mc.stop();
                }
            }
            default -> {}
        }
    }
}
