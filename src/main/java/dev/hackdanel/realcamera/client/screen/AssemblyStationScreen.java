package dev.hackdanel.realcamera.client.screen;

import dev.hackdanel.realcamera.menu.AssemblyStationMenu;
import dev.hackdanel.realcamera.recipe.AssemblyChoices;
import dev.hackdanel.realcamera.recipe.AssemblyInput;
import dev.hackdanel.realcamera.recipe.AssemblyRecipe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

public class AssemblyStationScreen extends AbstractContainerScreen<AssemblyStationMenu> {
    private static final int CELLS = AssemblyStationMenu.CHOICE_CELLS;
    private static final int ROW_X = AssemblyStationMenu.CHOICE_X, ROW_Y = AssemblyStationMenu.CHOICE_Y;
    private static final int PREV_X = ROW_X - 18, NEXT_X = ROW_X + CELLS * 18;

    /** Results of the recipes the inputs match, in the server's order (AssemblyChoices). */
    private List<ItemStack> choices = List.of();
    private List<ItemStack> choicesFor = List.of();
    private int first;
    private int shownIndex = -2;

    public AssemblyStationScreen(AssemblyStationMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageHeight = AssemblyStationMenu.INV_TOP + 82;
        inventoryLabelY = imageHeight - 94;
    }

    /** Re-lists the matching recipes when the inputs change; the row shows once it agrees with the server. */
    private List<ItemStack> choices() {
        List<ItemStack> inputs = menu.inputs();
        if (!sameStacks(inputs, choicesFor)) {
            choicesFor = inputs.stream().map(ItemStack::copy).toList();
            List<ItemStack> list = new ArrayList<>();
            if (minecraft != null && minecraft.level != null) {
                for (RecipeHolder<AssemblyRecipe> h : AssemblyChoices.find(minecraft.level.getRecipeManager(),
                        new AssemblyInput(inputs), minecraft.level)) {
                    list.add(h.value().getResultItem(minecraft.level.registryAccess()));
                }
            }
            choices = list;
        }
        return choices.size() == menu.choiceCount() && choices.size() > 1 ? choices : List.of();
    }

    private static boolean sameStacks(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** Keeps the running recipe in view when it changes. */
    private void follow(int count) {
        int index = menu.choiceIndex();
        if (index != shownIndex) {
            shownIndex = index;
            if (index >= 0 && (index < first || index >= first + CELLS)) {
                first = index - index % CELLS;
            }
        }
        first = Math.max(0, Math.min(first, Math.max(0, count - CELLS)));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        UiDraw.panel(g, leftPos, topPos, imageWidth, imageHeight);
        for (int i = 0; i < 6; i++) {
            UiDraw.slot(g, leftPos + 26 + (i % 3) * 18, topPos + 17 + (i / 3) * 18);
        }
        UiDraw.slot(g, leftPos + 134, topPos + 26);
        // progress arrow
        int ax = leftPos + 90, ay = topPos + 30;
        g.fill(ax, ay, ax + 30, ay + 8, 0xFF8B8B8B);
        g.fill(ax, ay, ax + Math.round(30 * menu.progress()), ay + 8, 0xFF3CB043);
        g.fill(ax + 30, ay - 3, ax + 34, ay + 11, 0xFF8B8B8B);
        UiDraw.playerInventory(g, leftPos, topPos, AssemblyStationMenu.INV_TOP);

        List<ItemStack> list = choices();
        if (list.isEmpty()) {
            return;
        }
        follow(list.size());
        g.drawString(font, Component.translatable("gui.realcamera.assembly.choose", list.size()),
                leftPos + ROW_X, topPos + ROW_Y - 10, 0x404040, false);
        for (int c = 0; c < CELLS && first + c < list.size(); c++) {
            int x = leftPos + ROW_X + c * 18, y = topPos + ROW_Y;
            UiDraw.slot(g, x, y);
            if (first + c == menu.choiceIndex()) {
                g.fill(x - 1, y - 1, x + 17, y + 17, 0x6040C040);
            } else if (inCell(mouseX, mouseY, x, y)) {
                g.fill(x, y, x + 16, y + 16, 0x40FFFFFF);
            }
            g.renderItem(list.get(first + c), x, y);
        }
        if (first > 0) {
            arrow(g, PREV_X, "<", mouseX, mouseY);
        }
        if (first + CELLS < list.size()) {
            arrow(g, NEXT_X, ">", mouseX, mouseY);
        }
    }

    private void arrow(GuiGraphics g, int rx, String text, int mouseX, int mouseY) {
        int x = leftPos + rx, y = topPos + ROW_Y;
        boolean hover = inCell(mouseX, mouseY, x, y);
        g.fill(x, y, x + 16, y + 16, hover ? 0xFFA0A0A0 : 0xFF8B8B8B);
        g.drawCenteredString(font, text, x + 8, y + 4, 0xFFFFFF);
    }

    private static boolean inCell(double mx, double my, int x, int y) {
        return mx >= x && mx < x + 16 && my >= y && my < y + 16;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<ItemStack> list = choices();
        if (button == 0 && !list.isEmpty()) {
            int y = topPos + ROW_Y;
            if (first > 0 && inCell(mx, my, leftPos + PREV_X, y)) {
                first = Math.max(0, first - CELLS);
                return true;
            }
            if (first + CELLS < list.size() && inCell(mx, my, leftPos + NEXT_X, y)) {
                first += CELLS;
                return true;
            }
            for (int c = 0; c < CELLS && first + c < list.size(); c++) {
                if (inCell(mx, my, leftPos + ROW_X + c * 18, y)) {
                    minecraft.gameMode.handleInventoryButtonClick(menu.containerId, first + c);
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        List<ItemStack> list = choices();
        for (int c = 0; c < CELLS && first + c < list.size(); c++) {
            if (inCell(mouseX, mouseY, leftPos + ROW_X + c * 18, topPos + ROW_Y)) {
                g.renderTooltip(font, list.get(first + c), mouseX, mouseY);
                return;
            }
        }
        renderTooltip(g, mouseX, mouseY);
    }
}
