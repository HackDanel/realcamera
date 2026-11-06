package dev.hackdanel.realcamera.mixin;

import dev.hackdanel.realcamera.component.Commission;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.ItemCost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Newspaper orders: a trade cost carrying a {@link Commission} accepts any print whose photo fulfils it (vanilla
 * would demand the exact components). Used by MerchantOffer#satisfiedBy and the trade screen's auto-fill alike.
 */
@Mixin(ItemCost.class)
public abstract class ItemCostMixin {
    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void realcamera$commission(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        Commission c = ((ItemCost) (Object) this).itemStack().get(RCComponents.COMMISSION.get());
        if (c != null) {
            PrintData print = stack.get(RCComponents.PRINT.get());
            boolean ok = stack.is(RCItems.PHOTO_PRINT.get()) && print != null && c.matches(print);
            // a paper buys photos from the photographer, and each picture only once
            net.minecraft.world.entity.player.Player trader = Commission.TRADER.get();
            if (ok && trader != null) {
                ok = print.photo().authorId().equals(trader.getUUID());
                if (ok && trader instanceof net.minecraft.server.level.ServerPlayer sp) {
                    ok = !dev.hackdanel.realcamera.gameplay.SoldPhotos.get(sp.server).isSold(sp.server, print.photo());
                }
            }
            cir.setReturnValue(ok);
        }
    }
}
