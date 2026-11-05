package dev.hackdanel.realcamera.entity;

import dev.hackdanel.realcamera.camera.CameraHelper;
import dev.hackdanel.realcamera.registry.RCEntities;
import dev.hackdanel.realcamera.registry.RCItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A tripod standing in the world. Right click with a camera mounts it; right click with an empty
 * hand looks through it (client side); sneak + right click takes the camera back; hitting it packs
 * the tripod (dropping the camera). Its rotation is the camera head's pan and tilt.
 */
public class TripodEntity extends Entity {
    private static final EntityDataAccessor<ItemStack> CAMERA =
            SynchedEntityData.defineId(TripodEntity.class, EntityDataSerializers.ITEM_STACK);

    /** Client hook: start looking through this tripod (installed by the client mod). */
    public static java.util.function.Consumer<TripodEntity> lookThrough = t -> {};

    public TripodEntity(EntityType<? extends TripodEntity> type, Level level) {
        super(type, level);
    }

    public TripodEntity(Level level, Vec3 pos, float yaw) {
        this(RCEntities.TRIPOD.get(), level);
        setPos(pos);
        setYRot(yaw);
        setXRot(0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(CAMERA, ItemStack.EMPTY);
    }

    public ItemStack camera() {
        return entityData.get(CAMERA);
    }

    /** Replace the mounted camera (a copy is stored so the change is synced to clients). */
    public void setCamera(ItemStack stack) {
        entityData.set(CAMERA, stack.copy());
    }

    /** Pan/tilt the head (server side, from the photographer looking through it). */
    public void aim(float yaw, float pitch) {
        setYRot(yaw);
        setXRot(Math.max(-89, Math.min(89, pitch)));
        yRotO = getYRot();
        xRotO = getXRot();
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (player.isSecondaryUseActive()) {
            if (!camera().isEmpty() && held.isEmpty()) {
                if (!level().isClientSide) {
                    player.setItemInHand(hand, camera().copy());
                    setCamera(ItemStack.EMPTY);
                    playSound(SoundEvents.ITEM_FRAME_REMOVE_ITEM, 1f, 1.2f);
                }
                return InteractionResult.sidedSuccess(level().isClientSide);
            }
            return InteractionResult.PASS;
        }
        if (camera().isEmpty() && CameraHelper.isCamera(held)) {
            if (!level().isClientSide) {
                setCamera(held.copy());
                held.setCount(0);
                aim(player.getYRot(), player.getXRot());
                playSound(SoundEvents.ITEM_FRAME_ADD_ITEM, 1f, 1.2f);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (!camera().isEmpty() && held.isEmpty()) {
            if (level().isClientSide) {
                lookThrough.accept(this);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source) || level().isClientSide || isRemoved()) {
            return false;
        }
        if (!camera().isEmpty()) {
            spawnAtLocation(camera().copy());
        }
        if (!(source.getEntity() instanceof Player p && p.hasInfiniteMaterials())) {
            spawnAtLocation(new ItemStack(RCItems.TRIPOD.get()));
        }
        playSound(SoundEvents.ARMOR_STAND_BREAK, 1f, 1.2f);
        discard();
        return true;
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(RCItems.TRIPOD.get());
    }

    @Override
    public void tick() {
        super.tick();
        if (!onGround()) {
            setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
        } else {
            setDeltaMovement(Vec3.ZERO);
        }
        move(net.minecraft.world.entity.MoverType.SELF, getDeltaMovement());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setCamera(tag.contains("camera") ? ItemStack.parseOptional(registryAccess(), tag.getCompound("camera")) : ItemStack.EMPTY);
        aim(tag.getFloat("yaw"), tag.getFloat("pitch"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (!camera().isEmpty()) {
            tag.put("camera", camera().save(registryAccess()));
        }
        tag.putFloat("yaw", getYRot());
        tag.putFloat("pitch", getXRot());
    }

    /** Lets the photographer's view use this entity's rotation directly. */
    @Override
    public float getViewYRot(float partialTicks) {
        return getYRot();
    }

    @Override
    public float getViewXRot(float partialTicks) {
        return getXRot();
    }
}
