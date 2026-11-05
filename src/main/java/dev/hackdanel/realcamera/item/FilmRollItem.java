package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.FilmRoll;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.spec.FilmStockSpec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** A roll of 135 or 120 film: fresh, in a camera, exposed (rewound), developed or ruined. */
public class FilmRollItem extends Item {
    public FilmRollItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static FilmRoll roll(ItemStack stack) {
        return stack.getOrDefault(RCComponents.FILM.get(), FilmRoll.fresh("135"));
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int st = roll(stack).state();
        if (st != FilmRoll.DEVELOPED && st != FilmRoll.RUINED) return net.minecraft.world.InteractionResultHolder.pass(stack);
        if (level.isClientSide) dev.hackdanel.realcamera.ClientBridge.get().openContactSheet(stack);
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceKey<FilmStockSpec> key = stack.get(RCComponents.FILM_STOCK.get());
        if (key == null) return super.getName(stack);
        FilmRoll roll = roll(stack);
        Component name = SpecNames.name("film_stock", key);
        return switch (roll.state()) {
            case FilmRoll.DEVELOPED -> Component.translatable("item.realcamera.film_roll.developed", name);
            case FilmRoll.RUINED -> Component.translatable("item.realcamera.film_roll.ruined", name);
            default -> name;
        };
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        FilmRoll roll = roll(stack);
        Specs.film(context.registries(), stack).ifPresent(spec -> {
            lines.add(Component.translatable("tooltip.realcamera.film", roll.format(), spec.iso(),
                    Component.translatable("tooltip.realcamera.film.process." + spec.process()))
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tooltip.realcamera.film.frames", roll.shot(), spec.frames(roll.format()))
                    .withStyle(ChatFormatting.GRAY));
        });
        lines.add(Component.translatable("tooltip.realcamera.film.state." + roll.state()).withStyle(ChatFormatting.DARK_GRAY));
        if (roll.state() == FilmRoll.DEVELOPED && roll.push() != 0) {
            lines.add(Component.translatable("tooltip.realcamera.film.push", (roll.push() > 0 ? "+" : "") + roll.push())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        if (roll.fogged()) {
            lines.add(Component.translatable("tooltip.realcamera.film.fogged").withStyle(ChatFormatting.RED));
        }
    }
}
