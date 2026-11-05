package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

public class MemoryCardItem extends Item {
    public MemoryCardItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceKey<MemoryCardTypeSpec> key = stack.get(RCComponents.CARD_TYPE.get());
        return key != null ? SpecNames.name("memory_card_type", key) : super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        CardData data = stack.getOrDefault(RCComponents.CARD.get(), CardData.EMPTY);
        Specs.card(context.registries(), stack).ifPresent(type -> lines.add(Component.translatable(
                "tooltip.realcamera.card_usage", data.photoCount(),
                String.format(Locale.ROOT, "%.1f", data.usedBytes() / 1e9), type.capacityGb(), type.writeMbS())
                .withStyle(ChatFormatting.GRAY)));
    }
}
