package dev.hackdanel.realcamera.entity;

import dev.hackdanel.realcamera.component.FrameData;
import dev.hackdanel.realcamera.item.PhotoPrintItem;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCEntities;
import dev.hackdanel.realcamera.registry.RCItems;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Wall frame of 1x1 to 4x3 blocks holding one print. Placement and bounding box follow vanilla
 * paintings. Right click with a print inserts it; sneak + right click cycles the mat (none / white /
 * black); hitting a filled frame first takes the print out.
 */
public class PhotoFrameEntity extends HangingEntity {
    private static final EntityDataAccessor<Integer> WIDTH = SynchedEntityData.defineId(PhotoFrameEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HEIGHT = SynchedEntityData.defineId(PhotoFrameEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> MATERIAL = SynchedEntityData.defineId(PhotoFrameEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> MAT = SynchedEntityData.defineId(PhotoFrameEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<ItemStack> PRINT = SynchedEntityData.defineId(PhotoFrameEntity.class, EntityDataSerializers.ITEM_STACK);

    public static final byte MAT_NONE = 0, MAT_WHITE = 1, MAT_BLACK = 2;

    public PhotoFrameEntity(EntityType<? extends PhotoFrameEntity> type, Level level) {
        super(type, level);
    }

    public PhotoFrameEntity(Level level, BlockPos pos, Direction direction, FrameData frame) {
        super(RCEntities.PHOTO_FRAME.get(), level, pos);
        entityData.set(WIDTH, frame.width());
        entityData.set(HEIGHT, frame.height());
        entityData.set(MATERIAL, frame.material());
        setDirection(direction);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(WIDTH, 1);
        builder.define(HEIGHT, 1);
        builder.define(MATERIAL, "oak");
        builder.define(MAT, MAT_WHITE);
        builder.define(PRINT, ItemStack.EMPTY);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == WIDTH || key == HEIGHT) {
            recalculateBoundingBox();
        }
    }

    public int frameWidth() {
        return entityData.get(WIDTH);
    }

    public int frameHeight() {
        return entityData.get(HEIGHT);
    }

    public String material() {
        return entityData.get(MATERIAL);
    }

    public byte mat() {
        return entityData.get(MAT);
    }

    public ItemStack print() {
        return entityData.get(PRINT);
    }

    public FrameData frameData() {
        return new FrameData(frameWidth(), frameHeight(), material());
    }

    @Override
    protected AABB calculateBoundingBox(BlockPos pos, Direction dir) {
        int w = entityData == null ? 1 : frameWidth();
        int h = entityData == null ? 1 : frameHeight();
        Vec3 center = Vec3.atCenterOf(pos).relative(dir, -0.46875);
        Direction side = dir.getCounterClockWise();
        Vec3 c = center.relative(side, w % 2 == 0 ? 0.5 : 0.0).relative(Direction.UP, h % 2 == 0 ? 0.5 : 0.0);
        Direction.Axis axis = dir.getAxis();
        double sx = axis == Direction.Axis.X ? 0.0625 : w;
        double sz = axis == Direction.Axis.Z ? 0.0625 : w;
        return AABB.ofSize(c, sx, h, sz);
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (print().isEmpty() && held.getItem() instanceof PhotoPrintItem && held.has(RCComponents.PRINT.get())) {
            if (!level().isClientSide) {
                entityData.set(PRINT, held.copyWithCount(1));
                if (player instanceof net.minecraft.server.level.ServerPlayer sp) {
                    dev.hackdanel.realcamera.gameplay.PhotoEvents.count(sp, "frames_hung");
                }
                held.consume(1, player);
                playSound(SoundEvents.ITEM_FRAME_ADD_ITEM, 1f, 1f);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (player.isSecondaryUseActive()) {
            if (!level().isClientSide) {
                entityData.set(MAT, (byte) ((mat() + 1) % 3));
                playSound(SoundEvents.ITEM_FRAME_ROTATE_ITEM, 1f, 1f);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        return InteractionResult.PASS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (isInvulnerableTo(source)) {
            return false;
        }
        if (!print().isEmpty() && !source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
            if (!level().isClientSide) {
                if (!(source.getEntity() instanceof Player p && p.hasInfiniteMaterials())) {
                    spawnAtLocation(print().copy());
                }
                entityData.set(PRINT, ItemStack.EMPTY);
                playSound(SoundEvents.ITEM_FRAME_REMOVE_ITEM, 1f, 1f);
            }
            return true;
        }
        return super.hurt(source, amount);
    }

    @Override
    public void dropItem(@Nullable Entity brokenBy) {
        if (!level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            return;
        }
        playSound(SoundEvents.PAINTING_BREAK, 1f, 1f);
        if (!print().isEmpty()) {
            spawnAtLocation(print().copy());
        }
        if (brokenBy instanceof Player p && p.hasInfiniteMaterials()) {
            return;
        }
        spawnAtLocation(frameItem());
    }

    public ItemStack frameItem() {
        ItemStack stack = new ItemStack(RCItems.PHOTO_FRAME.get());
        stack.set(RCComponents.FRAME.get(), frameData());
        return stack;
    }

    @Override
    public ItemStack getPickResult() {
        return frameItem();
    }

    @Override
    public void playPlacementSound() {
        playSound(SoundEvents.PAINTING_PLACE, 1f, 1f);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        tag.putByte("facing", (byte) direction.get2DDataValue());
        tag.putInt("frame_w", frameWidth());
        tag.putInt("frame_h", frameHeight());
        tag.putString("material", material());
        tag.putByte("mat", mat());
        if (!print().isEmpty()) {
            tag.put("print", print().save(registryAccess()));
        }
        super.addAdditionalSaveData(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        entityData.set(WIDTH, Math.max(1, tag.getInt("frame_w")));
        entityData.set(HEIGHT, Math.max(1, tag.getInt("frame_h")));
        entityData.set(MATERIAL, tag.getString("material"));
        entityData.set(MAT, tag.getByte("mat"));
        entityData.set(PRINT, tag.contains("print")
                ? ItemStack.parseOptional(registryAccess(), tag.getCompound("print")) : ItemStack.EMPTY);
        direction = Direction.from2DDataValue(tag.getByte("facing"));
        super.readAdditionalSaveData(tag);
        setDirection(direction);
    }

    @Override
    public void moveTo(double x, double y, double z, float yaw, float pitch) {
        setPos(x, y, z);
    }

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        setPos(x, y, z);
    }

    @Override
    public Vec3 trackingPosition() {
        return Vec3.atLowerCornerOf(pos);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket(ServerEntity entity) {
        return new ClientboundAddEntityPacket(this, direction.get3DDataValue(), getPos());
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        setDirection(Direction.from3DDataValue(packet.getData()));
    }
}
