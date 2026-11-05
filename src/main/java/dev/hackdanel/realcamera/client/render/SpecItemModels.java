package dev.hackdanel.realcamera.client.render;

import dev.hackdanel.realcamera.RealCamera;
import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.registry.RCItems;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * Item models chosen by data component: each catalog entry names its model in JSON. A camera body
 * with a lens is drawn as two render passes (body + the lens's "_mounted" model), all parts sharing
 * the same mount point, so new cameras need no code.
 */
@EventBusSubscriber(modid = RealCamera.MODID, value = Dist.CLIENT)
public final class SpecItemModels {
    private static final String[] FOLDERS = {"body", "lens", "battery", "card"};

    private SpecItemModels() {}

    @SubscribeEvent
    static void registerAdditional(ModelEvent.RegisterAdditional event) {
        var rm = Minecraft.getInstance().getResourceManager();
        for (String folder : FOLDERS) {
            rm.listResources("models/item/" + folder, rl -> rl.getPath().endsWith(".json")).keySet().forEach(rl -> {
                String path = rl.getPath().substring("models/".length(), rl.getPath().length() - ".json".length());
                event.register(ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(rl.getNamespace(), path)));
            });
        }
    }

    @SubscribeEvent
    static void modifyBaking(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        wrap(models, RCItems.CAMERA_BODY.get(), (r, s) -> Specs.body(r, s).map(b -> b.model()).orElse(null), true);
        wrap(models, RCItems.LENS.get(), (r, s) -> Specs.lens(r, s).map(l -> l.model()).orElse(null), false);
        wrap(models, RCItems.BATTERY.get(), (r, s) -> Specs.battery(r, s).map(b -> b.model()).orElse(null), false);
        wrap(models, RCItems.MEMORY_CARD.get(), (r, s) -> Specs.card(r, s).map(c -> c.model()).orElse(null), false);
    }

    private static void wrap(Map<ModelResourceLocation, BakedModel> models, Item item,
                             BiFunction<HolderLookup.Provider, ItemStack, ResourceLocation> modelOf, boolean camera) {
        ModelResourceLocation key = ModelResourceLocation.inventory(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
        BakedModel base = models.get(key);
        if (base == null) {
            RealCamera.LOGGER.warn("No base model for {}", key);
            return;
        }
        models.put(key, new Wrapper(base, new SpecOverrides(modelOf, camera)));
    }

    private static BakedModel standalone(ResourceLocation rl) {
        var manager = Minecraft.getInstance().getModelManager();
        BakedModel m = manager.getModel(ModelResourceLocation.standalone(rl));
        return m == manager.getMissingModel() ? null : m;
    }

    private static final class Wrapper extends BakedModelWrapper<BakedModel> {
        private final ItemOverrides overrides;

        Wrapper(BakedModel base, ItemOverrides overrides) {
            super(base);
            this.overrides = overrides;
        }

        @Override
        public ItemOverrides getOverrides() {
            return overrides;
        }
    }

    /** Two render passes sharing the body's display transforms. */
    private static final class Composite extends BakedModelWrapper<BakedModel> {
        private final List<BakedModel> passes;

        Composite(BakedModel body, BakedModel lens) {
            super(body);
            this.passes = List.of(body, lens);
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return passes;
        }

        /** The wrapper default returns the inner model here, which would drop the lens pass. */
        @Override
        public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext ctx,
                                         com.mojang.blaze3d.vertex.PoseStack pose, boolean leftHand) {
            originalModel.applyTransform(ctx, pose, leftHand);
            return this;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }

    private static final class SpecOverrides extends ItemOverrides {
        private final BiFunction<HolderLookup.Provider, ItemStack, ResourceLocation> modelOf;
        private final boolean camera;
        private final Map<String, BakedModel> composites = new ConcurrentHashMap<>();

        SpecOverrides(BiFunction<HolderLookup.Provider, ItemStack, ResourceLocation> modelOf, boolean camera) {
            this.modelOf = modelOf;
            this.camera = camera;
        }

        @Override
        public BakedModel resolve(BakedModel model, ItemStack stack, @Nullable ClientLevel level,
                                  @Nullable LivingEntity entity, int seed) {
            ClientLevel lvl = level != null ? level : Minecraft.getInstance().level;
            if (lvl == null) {
                return model;
            }
            ResourceLocation rl = modelOf.apply(lvl.registryAccess(), stack);
            BakedModel own = rl == null ? null : standalone(rl);
            if (own == null) {
                return model;
            }
            if (!camera) {
                return own;
            }
            ResourceLocation lensRl = Specs.lens(lvl.registryAccess(), CameraHelper.parts(stack).lens())
                    .map(l -> l.model().withSuffix("_mounted")).orElse(null);
            BakedModel lens = lensRl == null ? null : standalone(lensRl);
            if (lens == null) {
                return own;
            }
            return composites.computeIfAbsent(rl + "|" + lensRl, k -> new Composite(own, lens));
        }
    }
}
