package dev.hackdanel.realcamera.gameplay;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.component.CameraWear;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Real-world hazards for photo gear (plan §2.8): rain and water, drops, sensor dust, the cold,
 * and a mechanical shutter that eventually wears out.
 */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class WearEvents {
    private WearEvents() {}

    // ------------------------------------------------------------------ moisture

    /** Once a second: cameras get wet in rain (in hand) and in water (anywhere on the player), dry otherwise. */
    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || player.tickCount % 20 != 0) {
            return;
        }
        boolean rain = player.level().isRainingAt(BlockPos.containing(player.getEyePosition()));
        boolean underwater = player.isUnderWater();
        boolean swimming = player.isInWater();
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!CameraHelper.isCamera(stack)) continue;
            boolean inHand = stack == player.getMainHandItem() || stack == player.getOffhandItem();
            int sealing = Specs.body(player.registryAccess(), stack).map(CameraBodySpec::weatherSealing).orElse(0);
            CameraWear wear = CameraHelper.wear(stack);
            float m = wear.moisture();
            float delta;
            if (underwater || swimming) {
                float rate = inHand ? 25f : 6f;
                delta = rate * (sealing >= 2 ? 0.08f : sealing == 1 ? 0.5f : 1f);
            } else if (rain && inHand) {
                delta = sealing >= 2 ? 0f : sealing == 1 ? 0.25f : 3f;
            } else {
                delta = -0.6f;
            }
            float next = Math.max(0, Math.min(100, m + delta));
            if (next == m) continue;
            CameraWear updated = wear.withMoisture(next);
            // sealing slows water down (above) but no interchangeable-lens camera is submersible
            if (next >= 100 && !wear.dead()) {
                updated = updated.withDead(true);
                player.displayClientMessage(Component.translatable("message.realcamera.water_dead"), true);
                player.level().playSound(null, player.blockPosition(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5f, 1.6f);
            } else if (m < 40 && next >= 40) {
                player.displayClientMessage(Component.translatable("message.realcamera.fogged"), true);
            }
            stack.set(RCComponents.WEAR.get(), updated);
        }
    }

    // ------------------------------------------------------------------ drops

    /** A hard landing with a camera in hand: the UV filter cracks first, otherwise the lens gets knocked. */
    @SubscribeEvent
    static void onDamage(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !event.getSource().is(DamageTypeTags.IS_FALL)
                || event.getNewDamage() < 4) {
            return;
        }
        RandomSource rnd = player.getRandom();
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack camera = player.getItemInHand(hand);
            if (!CameraHelper.isCamera(camera) || rnd.nextFloat() > 0.5f) continue;
            CameraParts parts = CameraHelper.parts(camera);
            ItemStack filter = parts.filter();
            if (filter.is(dev.hackdanel.realcamera.registry.RCItems.FILTER.get()) && filter.getOrDefault(RCComponents.DAMAGE.get(), 0f) <= 0) {
                ItemStack cracked = filter.copy();
                cracked.set(RCComponents.DAMAGE.get(), 1f);
                CameraHelper.setParts(camera, parts.with(CameraParts.SLOT_FILTER, cracked));
                player.displayClientMessage(Component.translatable("message.realcamera.filter_cracked"), true);
                player.level().playSound(null, player.blockPosition(), SoundEvents.GLASS_BREAK, SoundSource.PLAYERS, 0.6f, 1.5f);
            } else if (!parts.lens().isEmpty() && !dev.hackdanel.realcamera.camera.Specs.body(player.registryAccess(), camera)
                    .map(b -> b.hasFixedLens()).orElse(false)) {
                ItemStack lens = parts.lens().copy();
                float damage = Math.min(1f, lens.getOrDefault(RCComponents.DAMAGE.get(), 0f) + 0.3f + rnd.nextFloat() * 0.3f);
                lens.set(RCComponents.DAMAGE.get(), damage);
                CameraHelper.setParts(camera, parts.with(CameraParts.SLOT_LENS, lens));
                player.displayClientMessage(Component.translatable("message.realcamera.lens_knocked"), true);
            }
        }
    }

    // ------------------------------------------------------------------ dust & cold & shutter

    /** Changing lenses in a dusty or wet place can let dust reach the sensor. */
    public static void lensChanged(Player player, ItemStack camera) {
        Level level = player.level();
        if (level.isClientSide) return;
        var biome = level.getBiome(player.blockPosition());
        boolean dusty = biome.is(Biomes.DESERT) || biome.is(BiomeTags.IS_BADLANDS) || biome.is(BiomeTags.IS_NETHER)
                || biome.is(Biomes.BEACH) || level.isRainingAt(player.blockPosition());
        RandomSource rnd = player.getRandom();
        if (rnd.nextFloat() < (dusty ? 0.5f : 0.05f)) {
            CameraWear wear = CameraHelper.wear(camera);
            int n = 1 + (dusty ? rnd.nextInt(3) : 0);
            for (int i = 0; i < n; i++) {
                wear = wear.withDust(new CameraWear.Dust(rnd.nextFloat() * 1.8f - 0.9f, rnd.nextFloat() * 1.8f - 0.9f,
                        0.004f + rnd.nextFloat() * 0.01f));
            }
            camera.set(RCComponents.WEAR.get(), wear);
        }
    }

    /** Batteries give less in the cold (snowy biomes, high mountains). */
    /** Charge units for a fractional drain: rounded up or down at random so the average is exact. */
    public static int units(Player player, float drain) {
        int whole = (int) Math.floor(drain);
        return whole + (player.getRandom().nextFloat() < drain - whole ? 1 : 0);
    }

    public static int coldDrain(Player player, int amount) {
        float temp = player.level().getBiome(player.blockPosition()).value().getBaseTemperature();
        if (temp < 0.15f) {
            return player.getRandom().nextFloat() < 0.5f ? amount * 2 : amount; // ×1.5 on average
        }
        return amount;
    }

    /**
     * The rated life is an average: a few shutters (about 5%) fail before it, with a chance rising towards it,
     * and after it the chance grows quickly.
     * @return true if it just jammed
     */
    public static boolean shutterWear(Player player, ItemStack camera, int ratedLife) {
        int count = CameraHelper.shutterCount(camera);
        if (ratedLife <= 0) return false;
        double x = count / (double) ratedLife;
        double p = 0.25 / ratedLife * Math.pow(Math.min(x, 1), 4);
        if (x > 1) p += 0.02 * (x - 1);
        if (player.getRandom().nextDouble() < Math.min(0.2, p)) {
            camera.set(RCComponents.WEAR.get(), CameraHelper.wear(camera).withJammed(true));
            player.displayClientMessage(Component.translatable("message.realcamera.shutter_jammed"), true);
            return true;
        }
        return false;
    }

}
