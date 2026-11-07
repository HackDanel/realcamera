package dev.hackdanel.realcamera.gameplay;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.registry.RCComponents;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A camera a player carries but does not hold hangs on its strap: everyone around sees it on their chest. Other
 * players' inventories are not sent to clients, so the server tells them which camera it is (only what the model
 * needs: the body and its parts) whenever that changes.
 */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class NeckCameras {
    private static final Map<UUID, ItemStack> shown = new ConcurrentHashMap<>();

    private NeckCameras() {}

    /** The first camera in the inventory that is not in a hand, reduced to what is drawn; empty if none. */
    static ItemStack carried(Player player) {
        if (CameraHelper.handHoldingCamera(player).isPresent()) return ItemStack.EMPTY;
        var inv = player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (CameraHelper.isCamera(s)) {
                ItemStack copy = new ItemStack(s.getItem());
                copy.set(RCComponents.BODY.get(), s.get(RCComponents.BODY.get()));
                var parts = CameraHelper.parts(s);
                CameraHelper.setParts(copy, parts.with(dev.hackdanel.realcamera.component.CameraParts.SLOT_BATTERY, ItemStack.EMPTY)
                        .with(dev.hackdanel.realcamera.component.CameraParts.SLOT_CARD, ItemStack.EMPTY));
                return copy;
            }
        }
        return ItemStack.EMPTY;
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || sp.tickCount % 10 != 0) return;
        ItemStack now = carried(sp);
        ItemStack before = shown.getOrDefault(sp.getUUID(), ItemStack.EMPTY);
        if (ItemStack.isSameItemSameComponents(now, before) && now.isEmpty() == before.isEmpty()) return;
        shown.put(sp.getUUID(), now);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(sp, new Payloads.NeckCamera(sp.getId(), now));
    }

    @SubscribeEvent
    static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof ServerPlayer target && event.getEntity() instanceof ServerPlayer viewer) {
            ItemStack s = shown.getOrDefault(target.getUUID(), ItemStack.EMPTY);
            if (!s.isEmpty()) PacketDistributor.sendToPlayer(viewer, new Payloads.NeckCamera(target.getId(), s));
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        shown.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        // a new entity id: send again on the next check
        shown.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        shown.remove(event.getEntity().getUUID());
    }
}
