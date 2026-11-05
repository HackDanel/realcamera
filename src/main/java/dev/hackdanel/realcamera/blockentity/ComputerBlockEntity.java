package dev.hackdanel.realcamera.blockentity;

import dev.hackdanel.realcamera.camera.Specs;
import dev.hackdanel.realcamera.component.CardData;
import dev.hackdanel.realcamera.item.MemoryCardItem;
import dev.hackdanel.realcamera.menu.ComputerMenu;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.network.ServerPhotoService;
import dev.hackdanel.realcamera.photo.CardStorage;
import dev.hackdanel.realcamera.photo.PhotoIndex;
import dev.hackdanel.realcamera.photo.PhotoStorage;
import dev.hackdanel.realcamera.registry.RCBlockEntities;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import dev.hackdanel.realcamera.spec.MemoryCardTypeSpec;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Computer: two card slots (catalogue, copy/move between cards) and a CFexpress reader upgrade slot.
 * Editing happens on the client; the result comes back as a new photo through the upload path.
 */
public class ComputerBlockEntity extends MachineBlockEntity {
    public static final int SLOT_CARD_A = 0, SLOT_CARD_B = 1, SLOT_READER = 2, SLOTS = 3;

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        super(RCBlockEntities.COMPUTER.get(), pos, state, SLOTS);
    }

    @Override
    protected boolean isItemValid(int slot, ItemStack stack) {
        return slot == SLOT_READER ? stack.is(RCItems.CFEXPRESS_READER.get()) : stack.getItem() instanceof MemoryCardItem;
    }

    @Override
    protected int slotLimit(int slot) {
        return 1;
    }

    @Nullable
    public UUID cardIdOrNull(int slot) {
        CardData d = items.getStackInSlot(slot).get(RCComponents.CARD.get());
        return d == null ? null : d.id().orElse(null);
    }

    /** The card's id, giving a never-used card one. Null if the slot holds no card. */
    @Nullable
    public UUID ensureCardId(int slot) {
        ItemStack card = items.getStackInSlot(slot);
        if (!(card.getItem() instanceof MemoryCardItem)) return null;
        CardData d = card.getOrDefault(RCComponents.CARD.get(), CardData.EMPTY);
        if (d.id().isPresent()) return d.id().get();
        UUID id = UUID.randomUUID();
        ItemStack copy = card.copy();
        copy.set(RCComponents.CARD.get(), new CardData(Optional.of(id), d.usedBytes(), d.photoCount()));
        items.setStackInSlot(slot, copy);
        return id;
    }

    /** SD cards always; CFexpress cards only with the reader upgrade. */
    public boolean readable(int slot) {
        ItemStack card = items.getStackInSlot(slot);
        if (card.isEmpty() || level == null) return false;
        String form = Specs.card(level.registryAccess(), card).map(MemoryCardTypeSpec::form).orElse("sd");
        return form.equals("sd") || items.getStackInSlot(SLOT_READER).is(RCItems.CFEXPRESS_READER.get());
    }

    public long freeBytes(int slot) {
        ItemStack card = items.getStackInSlot(slot);
        if (level == null) return 0;
        long cap = Specs.card(level.registryAccess(), card).map(MemoryCardTypeSpec::capacityBytes).orElse(0L);
        return cap - card.getOrDefault(RCComponents.CARD.get(), CardData.EMPTY).usedBytes();
    }

    public void adjustCard(int slot, long bytes, int count) {
        UUID id = cardIdOrNull(slot);
        if (id == null) return;
        ItemStack copy = items.getStackInSlot(slot).copy();
        if (ServerPhotoService.adjustCard(copy, id, bytes, count)) {
            items.setStackInSlot(slot, copy);
        }
    }

    private void resetCard(int slot) {
        UUID id = cardIdOrNull(slot);
        if (id == null) return;
        ItemStack copy = items.getStackInSlot(slot).copy();
        copy.set(RCComponents.CARD.get(), new CardData(Optional.of(id), 0, 0));
        items.setStackInSlot(slot, copy);
    }

    // ------------------------------------------------------------------ catalogue actions

    public static void handleAction(ServerPlayer player, Payloads.ComputerAction msg) {
        if (!(player.containerMenu instanceof ComputerMenu menu) || !menu.pos().equals(msg.pos())
                || menu.blockEntity() == null || msg.slot() < 0 || msg.slot() > 1) {
            return;
        }
        ComputerBlockEntity be = menu.blockEntity();
        int slot = msg.slot(), other = 1 - slot;
        UUID card = be.cardIdOrNull(slot);
        if (card == null || !be.readable(slot)) {
            return;
        }
        MinecraftServer server = player.server;
        CardStorage cards = PhotoStorage.cards(server);
        PhotoIndex index = PhotoStorage.index(server);
        switch (msg.action()) {
            case Payloads.PC_DELETE -> {
                long bytes = ServerPhotoService.removeFromCard(server, card, msg.photo());
                if (bytes >= 0) be.adjustCard(slot, -bytes, -1);
            }
            case Payloads.PC_RATE -> {
                PhotoIndex.Entry e = index.get(msg.photo());
                if (e != null && cards.contains(card, msg.photo())) {
                    index.put(e.withRating(Math.max(0, Math.min(5, msg.value()))));
                }
            }
            case Payloads.PC_COPY, Payloads.PC_MOVE -> {
                UUID target = be.ensureCardId(other);
                PhotoIndex.Entry e = index.get(msg.photo());
                if (target == null || !be.readable(other) || e == null || e.revoked()
                        || !cards.contains(card, msg.photo())) {
                    return;
                }
                if (cards.contains(target, msg.photo())) {
                    player.displayClientMessage(Component.translatable("message.realcamera.computer.already_there"), true);
                } else if (be.freeBytes(other) < e.cardBytes()) {
                    player.displayClientMessage(Component.translatable("message.realcamera.card_full"), true);
                } else {
                    cards.add(target, msg.photo());
                    be.adjustCard(other, e.cardBytes(), 1);
                    if (msg.action() == Payloads.PC_MOVE) {
                        long bytes = ServerPhotoService.removeFromCard(server, card, msg.photo());
                        if (bytes >= 0) be.adjustCard(slot, -bytes, -1);
                    }
                }
                PacketDistributor.sendToPlayer(player, ServerPhotoService.cardContents(player, target));
            }
            case Payloads.PC_FORMAT -> {
                for (UUID photo : List.copyOf(cards.photos(card))) {
                    ServerPhotoService.removeFromCard(server, card, photo);
                }
                be.resetCard(slot);
            }
            default -> {
                return;
            }
        }
        be.setChanged();
        PacketDistributor.sendToPlayer(player, ServerPhotoService.cardContents(player, card));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.realcamera.computer");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
        return new ComputerMenu(id, inv, this);
    }
}
