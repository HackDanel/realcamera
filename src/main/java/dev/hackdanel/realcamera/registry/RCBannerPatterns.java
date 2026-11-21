package dev.hackdanel.realcamera.registry;

import dev.hackdanel.realcamera.RealCamera;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BannerPatternItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Photo-themed banner patterns and their reusable loom templates. */
public final class RCBannerPatterns {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(RealCamera.MODID);

    public static final Entry APERTURE_STAR = add("aperture_star", "block.realcamera.banner.aperture_star");
    public static final Entry SHUTTER_125 = add("shutter_125", "block.realcamera.banner.shutter_125");

    private static Entry add(String id, String translationKey) {
        TagKey<BannerPattern> tag = TagKey.create(Registries.BANNER_PATTERN,
                RealCamera.id("pattern_item/" + id));
        DeferredItem<BannerPatternItem> item = ITEMS.register("banner_pattern_" + id,
                () -> new BannerPatternItem(tag, new Item.Properties()));
        return new Entry(tag, item);
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }

    public record Entry(TagKey<BannerPattern> tag, DeferredItem<BannerPatternItem> item) {}

    private RCBannerPatterns() {}
}
