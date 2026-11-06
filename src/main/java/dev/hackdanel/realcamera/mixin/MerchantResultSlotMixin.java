package dev.hackdanel.realcamera.mixin;

import dev.hackdanel.realcamera.component.Commission;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.registry.RCComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taking the result of a newspaper order: the order checks know the seller, and the sold picture is remembered so
 * it cannot be sold again (as a reprint, an enlargement or an edit).
 */
@Mixin(MerchantResultSlot.class)
public abstract class MerchantResultSlotMixin {
    @Shadow
    @Final
    private MerchantContainer slots;

    private static final ThreadLocal<PrintData> SELLING = new ThreadLocal<>();

    @Inject(method = "onTake", at = @At("HEAD"))
    private void realcamera$sell(Player player, ItemStack stack, CallbackInfo ci) {
        Commission.TRADER.set(player);
        SELLING.remove();
        MerchantOffer offer = slots.getActiveOffer();
        if (player instanceof ServerPlayer && offer != null && offer.getItemCostA().itemStack().has(RCComponents.COMMISSION.get())) {
            for (int i = 0; i < 2; i++) {
                PrintData print = slots.getItem(i).get(RCComponents.PRINT.get());
                if (print != null) {
                    SELLING.set(print);
                    break;
                }
            }
        }
    }

    @Inject(method = "onTake", at = @At("RETURN"))
    private void realcamera$sellDone(Player player, ItemStack stack, CallbackInfo ci) {
        Commission.TRADER.remove();
        PrintData print = SELLING.get();
        SELLING.remove();
        if (print == null || !(player instanceof ServerPlayer sp)) return;
        boolean stillThere = false;
        for (int i = 0; i < 2; i++) {
            stillThere |= print.equals(slots.getItem(i).get(RCComponents.PRINT.get()));
        }
        if (!stillThere) { // the trade went through and took the print
            dev.hackdanel.realcamera.gameplay.SoldPhotos.get(sp.server).markSold(sp.server, print.photo());
        }
    }
}
