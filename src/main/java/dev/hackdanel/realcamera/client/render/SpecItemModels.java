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
    private static final String[] FOLDERS = {"body", "lens", "battery", "card", "adapter", "teleconverter", "flash",
            "filter", "grip"};

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
        wrap(models, RCItems.MOUNT_ADAPTER.get(), (r, s) -> Specs.adapter(r, s).map(a -> a.model()).orElse(null), false);
        wrap(models, RCItems.TELECONVERTER.get(), (r, s) -> Specs.teleconverter(r, s).map(t -> t.model()).orElse(null), false);
        wrap(models, RCItems.FLASH.get(), (r, s) -> Specs.flash(r, s).map(f -> f.model()).orElse(null), false);
        wrap(models, RCItems.FILTER.get(), (r, s) -> Specs.filter(r, s).map(f -> f.model()).orElse(null), false);
        wrap(models, RCItems.BATTERY_GRIP.get(), (r, s) -> Specs.grip(r, s).map(g -> g.model()).orElse(null), false);
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

    /** Several render passes sharing the body's display transforms. */
    private static final class Composite extends BakedModelWrapper<BakedModel> {
        private final List<BakedModel> passes;

        Composite(BakedModel body, List<BakedModel> parts) {
            super(body);
            java.util.List<BakedModel> all = new java.util.ArrayList<>();
            all.add(body);
            all.addAll(parts);
            this.passes = List.copyOf(all);
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack stack, boolean fabulous) {
            return passes;
        }

        /** The wrapper default returns the inner model here, which would drop the extra passes. */
        @Override
        public BakedModel applyTransform(net.minecraft.world.item.ItemDisplayContext ctx,
                                         com.mojang.blaze3d.vertex.PoseStack pose, boolean leftHand) {
            if (CameraPose.raisedNow() && (ctx == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                    || ctx == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND)) {
                // the display transform is made for a hanging arm: turn the camera back by how far the arm is raised
                CameraPose.counterRotate(pose, leftHand);
            }
            originalModel.applyTransform(ctx, pose, leftHand);
            return this;
        }

        @Override
        public ItemOverrides getOverrides() {
            return ItemOverrides.EMPTY;
        }
    }

    /** A part model moved forward (towards -z) by the length of the parts behind it. */
    private static final class Shifted extends BakedModelWrapper<BakedModel> {
        private final net.neoforged.neoforge.client.model.IQuadTransformer transformer;
        private final Map<Object, List<net.minecraft.client.renderer.block.model.BakedQuad>> cache = new ConcurrentHashMap<>();

        Shifted(BakedModel model, float units) {
            super(model);
            this.transformer = net.neoforged.neoforge.client.model.QuadTransformers.applying(new com.mojang.math.Transformation(
                    new org.joml.Vector3f(0, 0, -units / 16f), null, null, null));
        }

        @Override
        public List<net.minecraft.client.renderer.block.model.BakedQuad> getQuads(
                @Nullable net.minecraft.world.level.block.state.BlockState state, @Nullable net.minecraft.core.Direction side,
                net.minecraft.util.RandomSource rand) {
            return cache.computeIfAbsent(side == null ? "none" : side,
                    k -> transformer.process(originalModel.getQuads(state, side, rand)));
        }

        @Override
        public List<net.minecraft.client.renderer.block.model.BakedQuad> getQuads(
                @Nullable net.minecraft.world.level.block.state.BlockState state, @Nullable net.minecraft.core.Direction side,
                net.minecraft.util.RandomSource rand, net.neoforged.neoforge.client.model.data.ModelData data,
                @Nullable net.minecraft.client.renderer.RenderType renderType) {
            return getQuads(state, side, rand);
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
            var r = lvl.registryAccess();
            var parts = CameraHelper.parts(stack);
            var adapter = Specs.adapter(r, parts.adapter()).orElse(null);
            var tc = Specs.teleconverter(r, parts.teleconverter()).orElse(null);
            var lens = Specs.lens(r, parts.lens()).orElse(null);
            var flash = Specs.flash(r, parts.flash()).orElse(null);
            var grip = Specs.grip(r, parts.grip()).orElse(null);
            String key = rl + "|" + (adapter == null ? "" : adapter.model()) + "|" + (tc == null ? "" : tc.model()) + "|"
                    + (lens == null ? "" : lens.model()) + "|" + (flash == null ? "" : flash.model()) + "|"
                    + (grip == null ? "" : grip.model());
            return composites.computeIfAbsent(key, k -> {
                java.util.List<BakedModel> chain = new java.util.ArrayList<>();
                float offset = 0;
                // model units are ~9 mm (see tools/gen_assets.py)
                if (adapter != null) {
                    BakedModel m = standalone(adapter.model().withSuffix("_mounted"));
                    if (m != null) chain.add(m);
                    offset += adapter.lengthMm() / 9f;
                }
                if (tc != null) {
                    BakedModel m = standalone(tc.model().withSuffix("_mounted"));
                    if (m != null) chain.add(offset == 0 ? m : new Shifted(m, offset));
                    offset += tc.lengthMm() / 9f;
                }
                if (lens != null) {
                    BakedModel m = standalone(lens.model().withSuffix("_mounted"));
                    if (m != null) chain.add(offset == 0 ? m : new Shifted(m, offset));
                }
                // accessories that sit on the body itself (hot shoe on top, grip underneath)
                if (flash != null) {
                    BakedModel m = standalone(flash.model().withSuffix("_mounted"));
                    if (m != null) chain.add(m);
                }
                if (grip != null) {
                    BakedModel m = standalone(grip.model().withSuffix("_mounted"));
                    if (m != null) chain.add(m);
                }
                return chain.isEmpty() ? own : new Composite(own, chain);
            });
        }
    }
}
