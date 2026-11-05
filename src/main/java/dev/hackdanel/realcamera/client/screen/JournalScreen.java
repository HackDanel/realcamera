package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.gameplay.Journal;
import dev.hackdanel.realcamera.gameplay.PhotoEvents;
import dev.hackdanel.realcamera.network.Payloads;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * The photo encyclopedia (plan §7.2): tabs for creatures, biomes, dimensions and phenomena; every page shows
 * the best photo taken of it with its stars, undiscovered pages stay grey. Opened with J or the field journal.
 */
public class JournalScreen extends Screen {
    private static Journal journal = Journal.EMPTY;
    private static List<String> dimensions = List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end");
    /** Living things that are not in a spawn category but deserve a page. */
    private static final Set<String> EXTRA = Set.of("minecraft:villager", "minecraft:wandering_trader",
            "minecraft:iron_golem", "minecraft:snow_golem", "minecraft:ender_dragon", "minecraft:wither");

    private static final String[] TABS = {Journal.CREATURE, Journal.BIOME, Journal.DIMENSION, Journal.PHENOMENON};
    private static final int W = 360, H = 232, COLS = 4, ROWS = 3, CELL_W = 84, CELL_H = 56;

    private int tab;
    private int page;
    private List<String> keys = List.of();

    public JournalScreen() {
        super(Component.translatable("screen.realcamera.journal"));
    }

    public static void onSync(Payloads.JournalSync msg) {
        journal = msg.journal();
        dimensions = msg.dimensions();
    }

    public static Journal journal() {
        return journal;
    }

    /** Every page the encyclopedia can have for a tab ("creature/" …), discovered ones first. */
    public static List<String> allKeys(String prefix) {
        List<String> ids = new ArrayList<>();
        switch (prefix) {
            case Journal.CREATURE -> {
                for (EntityType<?> t : BuiltInRegistries.ENTITY_TYPE) {
                    String id = BuiltInRegistries.ENTITY_TYPE.getKey(t).toString();
                    if (id.equals("minecraft:player")) continue;
                    if (t.getCategory() != MobCategory.MISC || EXTRA.contains(id)) ids.add(id);
                }
            }
            case Journal.BIOME -> {
                var level = Minecraft.getInstance().level;
                if (level != null) {
                    level.registryAccess().registryOrThrow(Registries.BIOME).keySet().forEach(k -> ids.add(k.toString()));
                }
            }
            case Journal.DIMENSION -> ids.addAll(dimensions);
            default -> ids.addAll(Journal.PHENOMENA);
        }
        List<String> keys = new ArrayList<>(ids.stream().map(i -> prefix + i).toList());
        // pages found by the player that the lists above do not know (e.g. a mod's custom dimension)
        journal.pages().keySet().stream().filter(k -> k.startsWith(prefix) && !keys.contains(k)).forEach(keys::add);
        keys.sort(Comparator.comparing((String k) -> !journal.pages().containsKey(k))
                .thenComparing(k -> PhotoEvents.pageName(k).getString()));
        return keys;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return (height - H) / 2;
    }

    @Override
    protected void init() {
        keys = allKeys(TABS[tab]);
        int x = left(), y = top();
        String[] names = {"creatures", "biomes", "dimensions", "phenomena"};
        for (int i = 0; i < TABS.length; i++) {
            int t = i;
            addRenderableWidget(Button.builder(Component.translatable("screen.realcamera.journal." + names[i]), b -> {
                tab = t;
                page = 0;
                rebuildWidgets();
            }).bounds(x + 6 + i * 88, y + 6, 86, 18).build()).active = i != tab;
        }
        int pages = Math.max(1, (keys.size() + COLS * ROWS - 1) / (COLS * ROWS));
        addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page = Math.floorMod(page - 1, pages);
        }).bounds(x + W - 64, y + H - 24, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page = Math.floorMod(page + 1, pages);
        }).bounds(x + W - 28, y + H - 24, 20, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = left(), y = top();
        int found = (int) keys.stream().filter(journal.pages()::containsKey).count();
        int pages = Math.max(1, (keys.size() + COLS * ROWS - 1) / (COLS * ROWS));
        page = Math.min(page, pages - 1);
        String progress = Component.translatable("screen.realcamera.journal.progress", found, keys.size(),
                keys.isEmpty() ? 0 : found * 100 / keys.size()).getString();
        g.drawString(font, progress, x + 8, y + H - 19, 0xFFE0D8C0, false);
        g.drawString(font, (page + 1) + "/" + pages, x + W - 96, y + H - 19, 0xFFB0A890, false);
        String hover = null;
        for (int i = 0; i < COLS * ROWS; i++) {
            int idx = page * COLS * ROWS + i;
            if (idx >= keys.size()) break;
            String key = keys.get(idx);
            int cx = x + 8 + (i % COLS) * (CELL_W + 3), cy = y + 30 + (i / COLS) * (CELL_H + 3);
            Journal.Page p = journal.pages().get(key);
            g.fill(cx, cy, cx + CELL_W, cy + CELL_H, p != null ? 0xFF2A2620 : 0xFF1A1816);
            if (p != null) {
                UiDraw.photo(g, p.photo(), cx + 2, cy + 2, CELL_W - 4, CELL_H - 16);
                String stars = "★".repeat(Math.max(1, p.stars())) + "☆".repeat(Math.max(0, 3 - p.stars()));
                g.drawString(font, stars, cx + CELL_W - 2 - font.width(stars), cy + CELL_H - 11, 0xFFFFD040, false);
            } else {
                g.drawCenteredString(font, "?", cx + CELL_W / 2, cy + (CELL_H - 14) / 2, 0xFF555048);
            }
            String name = PhotoEvents.pageName(key).getString();
            int maxW = CELL_W - (p != null ? 30 : 6);
            String shown = font.plainSubstrByWidth(name, maxW);
            g.drawString(font, shown, cx + 3, cy + CELL_H - 11, p != null ? 0xFFEDE6D0 : 0xFF6E685C, false);
            if (mouseX >= cx && mouseX < cx + CELL_W && mouseY >= cy && mouseY < cy + CELL_H) {
                hover = key;
            }
        }
        if (hover != null) {
            List<Component> lines = new ArrayList<>();
            lines.add(PhotoEvents.pageName(hover));
            Journal.Page p = journal.pages().get(hover);
            if (p != null) {
                lines.add(Component.translatable("screen.realcamera.journal.best", p.quality(), p.day()).withStyle(ChatFormatting.GRAY));
            } else {
                lines.add(Component.translatable("screen.realcamera.journal.unknown").withStyle(ChatFormatting.DARK_GRAY));
            }
            g.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left(), y = top();
        g.fill(x - 2, y - 2, x + W + 2, y + H + 2, 0xFF5A4630);
        g.fill(x, y, x + W, y + H, 0xFF3A3024);
        g.drawCenteredString(font, title, x + W / 2, y - 12, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
