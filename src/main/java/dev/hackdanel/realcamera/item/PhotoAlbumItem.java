package dev.hackdanel.realcamera.item;

import dev.hackdanel.realcamera.ClientBridge;
import dev.hackdanel.realcamera.component.AlbumData;
import dev.hackdanel.realcamera.component.PrintData;
import dev.hackdanel.realcamera.network.Payloads;
import dev.hackdanel.realcamera.registry.RCComponents;
import dev.hackdanel.realcamera.registry.RCItems;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** A book of up to 48 prints with captions. Can lie on a lectern for everyone to browse. */
@EventBusSubscriber(modid = dev.hackdanel.realcamera.RealCamera.MODID)
public class PhotoAlbumItem extends Item {
    public PhotoAlbumItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    public static AlbumData data(ItemStack album) {
        return album.getOrDefault(RCComponents.ALBUM.get(), AlbumData.EMPTY);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide) {
            ClientBridge.get().openAlbum(hand);
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("tooltip.realcamera.album", data(stack).prints().size(), AlbumData.CAPACITY)
                .withStyle(ChatFormatting.GRAY));
    }

    // ------------------------------------------------------------------ server

    public static void handleAction(ServerPlayer player, Payloads.AlbumAction msg) {
        if (msg.action() == Payloads.ALBUM_TAKE_FROM_LECTERN) {
            takeFromLectern(player, msg);
            return;
        }
        if (msg.hand() < 0 || msg.hand() > 1) return;
        InteractionHand hand = InteractionHand.values()[msg.hand()];
        ItemStack album = player.getItemInHand(hand);
        if (!album.is(RCItems.PHOTO_ALBUM.get())) return;
        AlbumData data = data(album);
        switch (msg.action()) {
            case Payloads.ALBUM_INSERT -> {
                if (data.prints().size() >= AlbumData.CAPACITY) return;
                ItemStack print = findPrint(player, hand);
                if (print.isEmpty()) {
                    player.displayClientMessage(Component.translatable("message.realcamera.album.no_print"), true);
                    return;
                }
                PrintData p = print.get(RCComponents.PRINT.get());
                print.shrink(1);
                album.set(RCComponents.ALBUM.get(), data.insert(msg.index(), p));
                player.level().playSound(null, player, SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1, 1);
            }
            case Payloads.ALBUM_TAKE -> {
                if (msg.index() < 0 || msg.index() >= data.prints().size()) return;
                ItemStack out = new ItemStack(RCItems.PHOTO_PRINT.get());
                out.set(RCComponents.PRINT.get(), data.prints().get(msg.index()));
                album.set(RCComponents.ALBUM.get(), data.remove(msg.index()));
                if (!player.getInventory().add(out)) player.drop(out, false);
            }
            case Payloads.ALBUM_CAPTION -> {
                if (msg.index() < 0 || msg.index() >= data.prints().size()) return;
                PrintData p = data.prints().get(msg.index());
                String text = msg.text().strip();
                album.set(RCComponents.ALBUM.get(), data.with(msg.index(),
                        new PrintData(p.photo(), p.paperSize(), p.finish(), p.missingInks(), text)));
            }
            default -> {}
        }
    }

    /** The print in the other hand, otherwise the first one in the inventory. */
    private static ItemStack findPrint(Player player, InteractionHand albumHand) {
        ItemStack other = player.getItemInHand(albumHand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        if (other.has(RCComponents.PRINT.get())) return other;
        for (ItemStack s : player.getInventory().items) {
            if (s.has(RCComponents.PRINT.get())) return s;
        }
        return ItemStack.EMPTY;
    }

    private static void takeFromLectern(ServerPlayer player, Payloads.AlbumAction msg) {
        if (player.distanceToSqr(msg.pos().getCenter()) > 64
                || !(player.level().getBlockEntity(msg.pos()) instanceof LecternBlockEntity lectern)
                || !lectern.getBook().is(RCItems.PHOTO_ALBUM.get()) || !player.mayBuild()) {
            return;
        }
        ItemStack book = lectern.getBook().copy();
        lectern.setBook(ItemStack.EMPTY);
        BlockState state = player.level().getBlockState(msg.pos());
        LecternBlock.resetBookState(player, player.level(), msg.pos(), state, false);
        if (!player.getInventory().add(book)) player.drop(book, false);
    }

    /** Right-clicking a lectern holding an album opens the album instead of the (empty) book screen. */
    @SubscribeEvent
    static void onUseLectern(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!(level.getBlockEntity(event.getPos()) instanceof LecternBlockEntity lectern)
                || !lectern.getBook().is(RCItems.PHOTO_ALBUM.get())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide));
        if (event.getEntity() instanceof ServerPlayer sp) {
            PacketDistributor.sendToPlayer(sp, new Payloads.OpenLecternAlbum(event.getPos(), data(lectern.getBook()).prints()));
        }
    }
}
