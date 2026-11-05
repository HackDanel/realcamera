package dev.hackdanel.realcamera.gameplay;

import com.google.common.collect.ImmutableSet;
import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.component.Commission;
import dev.hackdanel.realcamera.registry.RCBlocks;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.registry.RCSounds;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentPredicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The newspaper editor (plan §7.4): a villager profession working at the editorial desk. Sells no equipment;
 * its trades are photo orders ("a panda in a bamboo jungle, 2 stars") paid in emeralds, rarer subjects at higher
 * levels. A print fulfils an order when the server's analysis of its photo matches (see {@link Commission}).
 */
@EventBusSubscriber(modid = RealCamera.MODID)
public final class NewspaperEditor {
    private NewspaperEditor() {}

    public static final DeferredRegister<PoiType> POI = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, RealCamera.MODID);
    public static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, RealCamera.MODID);

    public static final ResourceKey<PoiType> DESK_KEY = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, RealCamera.id("editorial_desk"));
    public static final Supplier<PoiType> DESK = POI.register("editorial_desk",
            () -> new PoiType(ImmutableSet.copyOf(RCBlocks.EDITORIAL_DESK.get().getStateDefinition().getPossibleStates()), 1, 1));
    public static final Supplier<VillagerProfession> EDITOR = PROFESSIONS.register("newspaper_editor",
            () -> new VillagerProfession("newspaper_editor", h -> h.is(DESK_KEY), h -> h.is(DESK_KEY), ImmutableSet.of(),
                    ImmutableSet.of(), RCSounds.SHUTTER_MIRRORLESS.get()));

    /** One kind of order: what to photograph and how good, with the emerald range it pays. */
    private record Order(String entity, String biome, String phenomenon, int stars, int minPay, int maxPay) {
        Commission commission(int tier) {
            return new Commission(Optional.ofNullable(entity), Optional.ofNullable(biome), Optional.ofNullable(phenomenon),
                    stars, tier);
        }
    }

    private static Order creature(String id, int stars, int min, int max) {
        return new Order("minecraft:" + id, null, null, stars, min, max);
    }

    private static Order biome(String id, int stars, int min, int max) {
        return new Order(null, "minecraft:" + id, null, stars, min, max);
    }

    private static Order phenomenon(String id, int stars, int min, int max) {
        return new Order(null, null, id, stars, min, max);
    }

    /** Orders by villager level 1..5. */
    private static final List<List<Order>> ORDERS = List.of(
            List.of(creature("cow", 1, 2, 4), creature("pig", 1, 2, 4), creature("sheep", 1, 2, 4), creature("chicken", 1, 2, 3),
                    creature("rabbit", 1, 3, 5), creature("villager", 1, 2, 4), biome("plains", 1, 2, 3), biome("forest", 1, 2, 3),
                    biome("beach", 1, 2, 4)),
            List.of(creature("horse", 2, 4, 7), creature("fox", 2, 5, 8), creature("wolf", 2, 4, 7), creature("cat", 2, 4, 7),
                    creature("parrot", 2, 5, 8), creature("goat", 2, 5, 8), creature("frog", 2, 5, 8), creature("turtle", 2, 5, 8),
                    biome("desert", 1, 4, 6), biome("snowy_plains", 1, 4, 6), biome("jungle", 1, 5, 7), biome("swamp", 1, 4, 6)),
            List.of(new Order("minecraft:panda", "minecraft:bamboo_jungle", null, 2, 10, 14), creature("polar_bear", 2, 9, 13),
                    creature("axolotl", 2, 9, 13), creature("dolphin", 2, 9, 13), creature("bee", 2, 8, 12), creature("armadillo", 2, 9, 13),
                    creature("camel", 2, 8, 12), creature("sniffer", 2, 10, 14), phenomenon("sunset", 2, 8, 12),
                    phenomenon("rain", 2, 8, 11), biome("cherry_grove", 2, 8, 12), biome("badlands", 2, 8, 12)),
            List.of(creature("strider", 2, 14, 20), creature("ghast", 2, 14, 20), creature("piglin", 2, 12, 18),
                    creature("blaze", 2, 14, 20), creature("enderman", 2, 12, 18), creature("mooshroom", 2, 16, 22),
                    phenomenon("thunderstorm", 2, 14, 20), phenomenon("full_moon", 2, 14, 20), phenomenon("starry_night", 2, 12, 18),
                    biome("ice_spikes", 2, 14, 20), biome("mushroom_fields", 2, 16, 22)),
            List.of(creature("warden", 2, 32, 48), creature("ender_dragon", 2, 36, 56), creature("wither", 2, 32, 48),
                    creature("elder_guardian", 2, 28, 40), creature("breeze", 2, 24, 36), phenomenon("lightning", 2, 26, 40),
                    phenomenon("end_sky", 3, 24, 36), phenomenon("star_trails", 3, 26, 40)));

    private static final int[] XP = {2, 10, 20, 30, 30};

    private static VillagerTrades.ItemListing order(int level) {
        return (trader, random) -> {
            List<Order> pool = ORDERS.get(level - 1);
            Order o = pool.get(random.nextInt(pool.size()));
            return offer(o.commission(level), o.minPay + random.nextInt(o.maxPay - o.minPay + 1), XP[level - 1]);
        };
    }

    /** A trade that takes a print fulfilling the order and pays emeralds. */
    public static MerchantOffer offer(Commission c, int emeralds, int xp) {
        ItemCost cost = new ItemCost(RCItems.PHOTO_PRINT.get().builtInRegistryHolder(), 1,
                DataComponentPredicate.builder().expect(RCComponents.COMMISSION.get(), c).build());
        // price multiplier 0: demand never asks for a second print
        return new MerchantOffer(cost, Optional.empty(), new ItemStack(Items.EMERALD, Math.min(64, emeralds)), 2, xp, 0f);
    }

    @SubscribeEvent
    static void onTrades(VillagerTradesEvent event) {
        if (event.getType() != EDITOR.get()) return;
        var trades = event.getTrades();
        // level 1: buys paper too (no equipment for sale, ever)
        trades.get(1).add((trader, random) -> new MerchantOffer(new ItemCost(Items.PAPER, 24), new ItemStack(Items.EMERALD), 16, 2, 0.05f));
        for (int level = 1; level <= 5; level++) {
            trades.get(level).add(order(level));
            trades.get(level).add(order(level));
        }
    }

    @SubscribeEvent
    static void onTrade(TradeWithVillagerEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Commission c = event.getMerchantOffer().getItemCostA().itemStack().get(RCComponents.COMMISSION.get());
        if (c != null) {
            PhotoEvents.count(player, "commissions");
            PhotoEvents.event(player, "commission_tier", c.tier());
        }
    }
}
