package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.BatteryCharge;
import dev.hackdanel.realcamera.component.CameraParts;
import dev.hackdanel.realcamera.menu.CameraPartsMenu;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.CameraBodySpec;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * A camera body. Hold right click to raise it to the eye (viewfinder, handled client-side);
 * sneak + right click opens the body to swap lens, battery and card.
 */
public class CameraBodyItem extends Item {
    /** Viewfinder/EVF power use: one charge unit per this many ticks of use. */
    public static final int EVF_DRAIN_TICKS = 200;

    public CameraBodyItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    /** Bodies with a built-in lens always carry it (crafted, looted or creative stacks get it on the first tick). */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        if (level.isClientSide || level.getGameTime() % 10 != 0) return;
        dev.hackdanel.realcamera.camera.Specs.body(level.registryAccess(), stack).flatMap(CameraBodySpec::fixedLens).ifPresent(key -> {
            CameraParts parts = CameraHelper.parts(stack);
            if (!key.equals(parts.lens().get(RCComponents.LENS.get()))) {
                CameraHelper.setParts(stack, parts.with(CameraParts.SLOT_LENS, RCStacks.lens(key)));
            }
        });
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceKey<CameraBodySpec> key = stack.get(RCComponents.BODY.get());
        return key != null ? SpecNames.name("camera_body", key) : super.getName(stack);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            if (player instanceof ServerPlayer sp) {
                sp.openMenu(new SimpleMenuProvider((id, inv, p) -> new CameraPartsMenu(id, inv, hand),
                        stack.getHoverName()), buf -> buf.writeEnum(hand));
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
        }
        CameraParts parts = CameraHelper.parts(stack);
        if (CameraHelper.wear(stack).dead()) {
            player.displayClientMessage(Component.translatable("message.realcamera.camera_dead"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (parts.lens().isEmpty()) {
            player.displayClientMessage(Component.translatable("message.realcamera.no_lens"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (dev.hackdanel.realcamera.camera.OpticalChain.resolve(level.registryAccess(), stack).isEmpty()) {
            player.displayClientMessage(Component.translatable("message.realcamera.lens_mismatch"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (!CameraHelper.canFire(level.registryAccess(), stack)) {
            player.displayClientMessage(Component.translatable(parts.battery().isEmpty()
                    ? "message.realcamera.no_battery" : "message.realcamera.battery_empty"), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.SPYGLASS;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide) {
            return;
        }
        int used = getUseDuration(stack, entity) - remaining;
        boolean evf = dev.hackdanel.realcamera.camera.Specs.body(level.registryAccess(), stack)
                .map(b -> b.viewfinder().equals("evf")).orElse(true);
        if (evf && used > 0 && used % EVF_DRAIN_TICKS == 0) {
            CameraHelper.drain(stack, 1);
            if (!CameraHelper.canFire(level.registryAccess(), stack)) {
                entity.stopUsingItem();
            }
        }
    }

    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        // Battery drain and settings changes rewrite components; do not bob the item for that.
        return slotChanged || oldStack.getItem() != newStack.getItem()
                || !java.util.Objects.equals(oldStack.get(RCComponents.BODY.get()), newStack.get(RCComponents.BODY.get()));
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        var registries = context.registries();
        Specs.body(registries, stack).ifPresent(body -> {
            lines.add(Component.translatable("tooltip.realcamera.sensor",
                    String.format(Locale.ROOT, "%.1f", body.sensor().megapixels()),
                    String.format(Locale.ROOT, "%.1f×%.1f", body.sensor().widthMm(), body.sensor().heightMm()),
                    String.format(Locale.ROOT, "%.2f", body.sensor().cropFactor())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tooltip.realcamera.mount",
                    SpecNames.name("mount", body.mount())).withStyle(ChatFormatting.GRAY));
        });
        CameraParts parts = CameraHelper.parts(stack);
        lines.add(parts.lens().isEmpty()
                ? Component.translatable("tooltip.realcamera.no_lens").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("tooltip.realcamera.lens", parts.lens().getHoverName()).withStyle(ChatFormatting.GRAY));
        BatteryCharge charge = parts.battery().get(RCComponents.CHARGE.get());
        lines.add(charge == null
                ? Component.translatable("tooltip.realcamera.no_battery").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("tooltip.realcamera.battery", charge.percent()).withStyle(ChatFormatting.GRAY));
        long left = CameraHelper.shotsLeft(registries, stack);
        lines.add(left < 0
                ? Component.translatable("tooltip.realcamera.no_card").withStyle(ChatFormatting.DARK_GRAY)
                : Component.translatable("tooltip.realcamera.shots_left", left).withStyle(ChatFormatting.GRAY));
        var wear = CameraHelper.wear(stack);
        if (wear.dead()) lines.add(Component.translatable("tooltip.realcamera.wear.dead").withStyle(ChatFormatting.RED));
        if (wear.shutterJammed()) lines.add(Component.translatable("tooltip.realcamera.wear.jammed").withStyle(ChatFormatting.RED));
        if (wear.moisture() >= 40) lines.add(Component.translatable("tooltip.realcamera.wear.moist").withStyle(ChatFormatting.BLUE));
        if (!wear.dust().isEmpty()) lines.add(Component.translatable("tooltip.realcamera.wear.dust", wear.dust().size())
                .withStyle(ChatFormatting.GOLD));
        if (flag.isAdvanced()) {
            lines.add(Component.translatable("tooltip.realcamera.shutter_count", CameraHelper.shutterCount(stack))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        lines.add(Component.translatable("tooltip.realcamera.camera_hint").withStyle(ChatFormatting.DARK_AQUA));
    }
}
