package dev.hackdanel.realcamera.mixin;

import dev.hackdanel.realcamera.component.Commission;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.item.trading.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells newspaper orders ({@link Commission}) who is offering a print while the trade screen matches offers. */
@Mixin(MerchantContainer.class)
public abstract class MerchantContainerMixin {
    @Shadow
    @Final
    private Merchant merchant;

    @Inject(method = "updateSellItem", at = @At("HEAD"))
    private void realcamera$trader(CallbackInfo ci) {
        Commission.TRADER.set(merchant.getTradingPlayer());
    }

    @Inject(method = "updateSellItem", at = @At("RETURN"))
    private void realcamera$traderDone(CallbackInfo ci) {
        Commission.TRADER.remove();
    }
}
