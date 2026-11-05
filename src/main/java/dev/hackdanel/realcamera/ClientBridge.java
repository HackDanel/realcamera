package dev.hackdanel.realcamera;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Hooks from common code into client-only code. The client mod class installs the real
 * implementation; on a dedicated server the no-op default is used and no client class is loaded.
 */
public interface ClientBridge {
    ClientBridge NOOP = new ClientBridge() {};

    default void openPrintViewer(ItemStack print) {}

    default void openCameraMenu() {}

    default void openPlayback() {}

    default void openAlbum(net.minecraft.world.InteractionHand hand) {}

    /** Server -> client payloads are dispatched here so common code never references client classes. */
    default void handlePayload(CustomPacketPayload payload) {}

    final class Holder {
        public static ClientBridge instance = NOOP;

        private Holder() {}
    }

    static ClientBridge get() {
        return Holder.instance;
    }
}
