package com.sts15.enderdrives.screen;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.sts15.enderdrives.client.ClientConfigCache;
import com.sts15.enderdrives.config.serverConfig;
import com.sts15.enderdrives.items.EnderDiskItem;
import com.sts15.enderdrives.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static com.sts15.enderdrives.Constants.MOD_ID;
import static com.sts15.enderdrives.screen.EnderDiskFrequencyScreen.useAltTheme;

public class EnderDiskFrequencyScreen extends Screen {

    private ResourceLocation getEnderDiskMask() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/ender_disk_mask_alt.png" : "textures/gui/ender_disk_mask.png");
    }
    private ResourceLocation getArrowUpHover() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/up_arrow_highlight_alt.png" : "textures/gui/up_arrow_highlight.png");
    }
    private ResourceLocation getArrowDownHover() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/down_arrow_highlight_alt.png" : "textures/gui/down_arrow_highlight.png");
    }
    private ResourceLocation getButtonTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/scope_button_alt.png" : "textures/gui/scope_button.png");
    }
    private ResourceLocation getButtonHoverTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/scope_button_hover_alt.png" : "textures/gui/scope_button_hover.png");
    }
    private static final Component TITLE = Component.translatable("screen.enderdrives.frequency_selector");
    public static final int WINDOW_WIDTH = 176;
    public static final int WINDOW_HEIGHT = 105;
    private int leftPos, topPos;
    private static final int[] SELECTOR_X = {33, 83, 133};
    private static final int SELECTOR_Y = 37;
    private static final int ARROW_WIDTH = 8;
    private static final int ARROW_HEIGHT = 11;
    private static final int ARROW_SPRITE_SIZE = 16;
    private static final int[] ARROW_TOP_X = {30, 80, 130};
    private static final int ARROW_TOP_Y = 18;
    private static final int[] ARROW_BOTTOM_X = ARROW_TOP_X;
    private static final int ARROW_BOTTOM_Y = 63;
    private EditBox frequencyField;
    private static final int MAX_FREQUENCY = ClientConfigCache.freqMax;
    private static final int MIN_FREQUENCY = ClientConfigCache.freqMin;
    private int[] dyeIndices = new int[3];
    private int frequency;
    FrequencyScope currentScope;
    private boolean ftbTeamsLoaded = ModList.get().isLoaded("ftbteams");
    private final List<Particle2D> uiParticles = new ArrayList<>();
    private float particleSpawnTimer = 0f;
    private final Random random = new Random();
    private int transferMode = TransferMode.BIDIRECTIONAL;
    private CustomImageCycleButton transferButton;
    public static boolean useAltTheme = true;
    public static final int TEXT_COLOR = useAltTheme ? 0xFFE44C : 0xFFFFFF;
    public static final int SHADOW_COLOR = useAltTheme ? 0x993F00 : 0xFFFFFF;

    public EnderDiskFrequencyScreen(int currentFrequency, FrequencyScope scope, int transferMode) {
        super(Component.translatable("screen.enderdrives.frequency"));
        this.frequency = currentFrequency;
        this.currentScope = scope;
        this.transferMode = transferMode;
        decodeFrequency();
    }

    @Override
    protected void init() {
        if (!currentScope.isEnabled()) {
            currentScope = FrequencyScope.getDefault();
        }
        this.leftPos = (this.width - WINDOW_WIDTH) / 2;
        this.topPos = (this.height - WINDOW_HEIGHT) / 2;
        int fieldX = leftPos + 9;
        int fieldY = topPos + 89;

        frequencyField = new EditBox(
                Minecraft.getInstance().font,
                fieldX, fieldY,
                70, 12,
                Component.translatable("screen.enderdrives.frequency_placeholder")
        );
        frequencyField.setMaxLength(4);
        frequencyField.setValue(String.valueOf(frequency));
        frequencyField.setResponder(this::onFrequencyFieldChanged);
        frequencyField.setBordered(false);
        frequencyField.setVisible(true);
        frequencyField.setTextColor(TEXT_COLOR);
        frequencyField.setTextShadow(true);
        frequencyField.setCanLoseFocus(true);
        this.addRenderableWidget(frequencyField);

        int transferButtonX = leftPos + (useAltTheme ? 102 : 100);
        transferButton = new CustomImageCycleButton(
                transferButtonX, topPos + 85,
                15, 14,
                b -> {
                    transferMode = TransferMode.next(transferMode);
                    transferButton.setMode(transferMode);
                },
                transferMode
        );
        this.addRenderableWidget(transferButton);

        int scopeButtonX = leftPos + (useAltTheme ? 120 : 118);
        Button customButton = new CustomImageButton(
                scopeButtonX, topPos + 85,
                50, 14,
                b -> cycleScope(),
                getButtonTexture(),
                getButtonHoverTexture(),
                this
        );
        this.addRenderableWidget(customButton);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderUIParticles(graphics, partialTick);
        drawColorSelectors(graphics);
        drawCustomBackground(graphics);
        drawTitle(graphics);
        drawArrowButtons(graphics, mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);

        if (frequencyField.getValue().isEmpty() && !frequencyField.isFocused()) {
            graphics.drawString(
                    Minecraft.getInstance().font,
                    Component.translatable("screen.enderdrives.frequency_label"),
                    frequencyField.getX() + 2,
                    frequencyField.getY() + 2,
                    TEXT_COLOR,
                    false
            );
        }

        for (int i = 0; i < 3; i++) {
            int topX = leftPos + ARROW_TOP_X[i];
            int topY = topPos + ARROW_TOP_Y;
            int bottomX = leftPos + ARROW_BOTTOM_X[i];
            int bottomY = topPos + ARROW_BOTTOM_Y;
            int size = 16;
            if (isHovering(mouseX, mouseY, topX, topY, size, size)) {
                graphics.renderTooltip(font, Component.translatable(getTooltipText(i, true)), mouseX, mouseY);
                return;
            }
            if (isHovering(mouseX, mouseY, bottomX, bottomY, size, size)) {
                graphics.renderTooltip(font, Component.translatable(getTooltipText(i, false)), mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Do nothing — prevent accessibility blur from rendering.
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void cycleScope() {
        int nextId = (currentScope.id + 1) % 3;
        if (nextId == FrequencyScope.TEAM.id && !ftbTeamsLoaded) nextId = 0;
        currentScope = FrequencyScope.fromId(nextId);
    }

    private String getTooltipText(int index, boolean isTop) {
        int value = switch (index) {
            case 0 -> 256;
            case 1 -> 16;
            case 2 -> 1;
            default -> 0;
        };

        return isTop
                ? "screen.enderdrives.tooltip.plus_" + value
                : "screen.enderdrives.tooltip.minus_" + value;
    }

    private void drawCustomBackground(GuiGraphics graphics) {
        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, -200);
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.endPortal());
        Matrix4f matrix = graphics.pose().last().pose();
        int shaderX = leftPos + 8;
        int shaderY = topPos + 18;
        int shaderW = 162;
        int shaderH = 68;
        vertexConsumer.addVertex(matrix, shaderX, shaderY + shaderH, 0).setColor(255, 255, 255, 255).setUv(0, 1);
        vertexConsumer.addVertex(matrix, shaderX + shaderW, shaderY + shaderH, 0).setColor(255, 255, 255, 255).setUv(1, 1);
        vertexConsumer.addVertex(matrix, shaderX + shaderW, shaderY, 0).setColor(255, 255, 255, 255).setUv(1, 0);
        vertexConsumer.addVertex(matrix, shaderX, shaderY, 0).setColor(255, 255, 255, 255).setUv(0, 0);
        bufferSource.endBatch(RenderType.endPortal());
        graphics.pose().popPose();
        graphics.blit(getEnderDiskMask(), leftPos, topPos, 0, 0, 176, 105);
    }

    private void renderUIParticles(GuiGraphics graphics, float partialTick) {
        float deltaTime = Minecraft.getInstance().getFrameTimeNs() / 1_000_000_000f;
        particleSpawnTimer += deltaTime;
        while (particleSpawnTimer > 0.05f) {
            spawnParticle();
            particleSpawnTimer -= 0.05f;
        }
        int menuLeft = leftPos;
        int menuTop = topPos;
        int menuRight = leftPos + WINDOW_WIDTH;
        int menuBottom = topPos + WINDOW_HEIGHT;
        uiParticles.removeIf(p -> !p.isAlive(menuLeft, menuTop, menuRight, menuBottom));
        for (Particle2D p : uiParticles) {
            p.update(deltaTime);
            int alpha = (int) (255 * (1f - p.age / p.lifetime));
            int size = (int)p.size;
            graphics.fill((int)p.x, (int)p.y, (int)p.x + size, (int)p.y + size, p.getColor());
        }
    }

    private void spawnParticle() {
        int centerX = leftPos + WINDOW_WIDTH / 2;
        int centerY = topPos + WINDOW_HEIGHT / 2;
        int margin = 20;
        int screenW = this.width;
        int screenH = this.height;
        int x, y;

        switch (random.nextInt(4)) {
            case 0 -> {
                x = random.nextInt(leftPos - margin, leftPos + WINDOW_WIDTH + margin);
                y = random.nextInt(topPos - margin);
            }
            case 1 -> {
                x = random.nextInt(leftPos - margin, leftPos + WINDOW_WIDTH + margin);
                y = random.nextInt(topPos + WINDOW_HEIGHT + margin, screenH);
            }
            case 2 -> {
                x = random.nextInt(leftPos - margin);
                y = random.nextInt(topPos, topPos + WINDOW_HEIGHT);
            }
            default -> {
                x = random.nextInt(leftPos + WINDOW_WIDTH + margin, screenW);
                y = random.nextInt(topPos, topPos + WINDOW_HEIGHT);
            }
        }
        float dx = centerX - x;
        float dy = centerY - y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        dx /= dist;
        dy /= dist;
        float speed = 40f + random.nextFloat() * 20f;
        float lifetime = dist / speed;
        uiParticles.add(new Particle2D(x, y, dx * speed, dy * speed, lifetime));
    }

    private void drawColorSelectors(GuiGraphics graphics) {
        for (int i = 0; i < 3; i++) {
            int x = leftPos + SELECTOR_X[i] - 3;
            int y = topPos + SELECTOR_Y - 2;
            DyeColor dye = DyeColor.byId(dyeIndices[i]);
            int color = dye.getTextColor();
            fillRoundedRect(graphics, x, y, 16, 30, 3, FastColor.ARGB32.color(255, color));
        }
    }

    private void fillRoundedRect(GuiGraphics graphics, int x, int y, int width, int height, int radius, int color) {
        graphics.fill(x + radius, y, x + width - radius, y + height, color);
        graphics.fill(x, y + radius, x + radius, y + height - radius, color);
        graphics.fill(x + width - radius, y + radius, x + width, y + height - radius, color);
        fillCircle(graphics, x + radius, y + radius, radius, color);
        fillCircle(graphics, x + width - radius - 1, y + radius, radius, color);
        fillCircle(graphics, x + radius, y + height - radius - 1, radius, color);
        fillCircle(graphics, x + width - radius - 1, y + height - radius - 1, radius, color);
    }

    private void fillCircle(GuiGraphics graphics, int centerX, int centerY, int radius, int color) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    graphics.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, color);
                }
            }
        }
    }

    private void drawTitle(GuiGraphics graphics) {
        int titleX = leftPos + 12;
        int titleY = topPos + (useAltTheme ? 6 : 4); // Already adjusted
        graphics.drawString(Minecraft.getInstance().font, TITLE, titleX, titleY, TEXT_COLOR, false);
    }


    private void drawArrowButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        for (int i = 0; i < 3; i++) {
            int topX = leftPos + ARROW_TOP_X[i];
            int topY = topPos + ARROW_TOP_Y;
            int bottomX = leftPos + ARROW_BOTTOM_X[i];
            int bottomY = topPos + ARROW_BOTTOM_Y + 1;
            if (isHovering(mouseX, mouseY, topX + 4, topY + 2, ARROW_WIDTH, ARROW_HEIGHT)) {
                graphics.blit(getArrowUpHover(), topX, topY, 0, 0, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE);
            }

            if (isHovering(mouseX, mouseY, bottomX + 4, bottomY + 2, ARROW_WIDTH, ARROW_HEIGHT)) {
                graphics.blit(getArrowDownHover(), bottomX, bottomY, 0, 0, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) return false;

        for (int i = 0; i < 3; i++) {
            int topX = leftPos + ARROW_TOP_X[i];
            int topY = topPos + ARROW_TOP_Y;
            int bottomX = leftPos + ARROW_BOTTOM_X[i];
            int bottomY = topPos + ARROW_BOTTOM_Y;
            final int hitboxSize = 16;
            if (isHovering((int) mouseX, (int) mouseY, topX, topY, hitboxSize, hitboxSize)) {
                dyeIndices[i] = (dyeIndices[i] + 1) % 16;
                updateFrequency();
                return true;
            }
            if (isHovering((int) mouseX, (int) mouseY, bottomX, bottomY, hitboxSize, hitboxSize)) {
                dyeIndices[i] = (dyeIndices[i] - 1 + 16) % 16;
                updateFrequency();
                return true;
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isHovering(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void decodeFrequency() {
        dyeIndices[0] = (frequency >> 8) & 0xF;
        dyeIndices[1] = (frequency >> 4) & 0xF;
        dyeIndices[2] = frequency & 0xF;
    }

    private void updateFrequency() {
        int newFreq = (dyeIndices[0] << 8) | (dyeIndices[1] << 4) | dyeIndices[2];
        int min = serverConfig.FREQ_MIN.get();
        int max = serverConfig.FREQ_MAX.get();

        frequency = Math.max(min, Math.min(newFreq, max)); // clamp to min/max

        if (frequencyField != null) {
            frequencyField.setValue(String.valueOf(frequency));
        }
    }


    private void onFrequencyFieldChanged(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value < MIN_FREQUENCY || value > MAX_FREQUENCY) return;
            frequency = value;
            decodeFrequency();
        } catch (NumberFormatException ignored) {}
    }


    @Override
    public void onClose() {
        super.onClose();
        int min = serverConfig.FREQ_MIN.get();
        int max = serverConfig.FREQ_MAX.get();
        int safeFreq = Math.max(min, Math.min(frequency, max));
        NetworkHandler.sendFrequencyUpdateToServer(safeFreq, currentScope, transferMode);
        if (currentScope == FrequencyScope.TEAM) {
            Player player = Minecraft.getInstance().player;
            if (player != null) {
                ItemStack held = player.getMainHandItem();
                if (held.getItem() instanceof EnderDiskItem) {
                    EnderDiskItem.updateTeamInfo(held, player);
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open(int currentFreq, FrequencyScope scope, int transferMode) {
        Minecraft.getInstance().setScreen(new EnderDiskFrequencyScreen(currentFreq, scope, transferMode));
    }

}

class CustomImageButton extends Button {
    private final ResourceLocation normalTexture;
    private final ResourceLocation hoverTexture;
    private final EnderDiskFrequencyScreen parent;
    public CustomImageButton(int x, int y, int width, int height, OnPress onPress,
                             ResourceLocation normalTexture, ResourceLocation hoverTexture,
                             EnderDiskFrequencyScreen parent) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.normalTexture = normalTexture;
        this.hoverTexture = hoverTexture;
        this.parent = parent;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ResourceLocation texture = isHovered ? hoverTexture : normalTexture;
        graphics.blit(texture, getX(), getY(), 0, 0, width, height, width, height);
        Component label = Component.translatable(parent.currentScope.translationKey());
        int textWidth = Minecraft.getInstance().font.width(label);
        int textX = getX() + (width - textWidth) / 2;
        int textY = getY() + (height - 8) / 2 ;//- (EnderDiskFrequencyScreen.useAltTheme ? 1 : 0);
        graphics.drawString(Minecraft.getInstance().font, label, textX, textY, EnderDiskFrequencyScreen.TEXT_COLOR, false);
        if (isHovered) {
            graphics.renderTooltip(Minecraft.getInstance().font, Component.translatable("screen.enderdrives.toggle_scope"), mouseX, mouseY);
        }
    }
}

class Particle2D {
    float x, y;
    float dx, dy;
    float lifetime;
    float age = 0f;
    float size;
    float flickerOffset;

    public Particle2D(float x, float y, float dx, float dy, float lifetime) {
        this.x = x;
        this.y = y;
        this.dx = dx;
        this.dy = dy;
        this.lifetime = lifetime;
        this.size = 2f + (float)Math.random() * 2f;
        this.flickerOffset = (float)Math.random() * (float)Math.PI * 2;
    }

    public void update(float delta) {
        x += dx * delta;
        y += dy * delta;
        age += delta;
    }

    public boolean isAlive(int menuLeft, int menuTop, int menuRight, int menuBottom) {
        boolean insideMenu = x >= menuLeft && x <= menuRight && y >= menuTop && y <= menuBottom;
        return age < lifetime && !insideMenu;
    }

    public int getColor() {
        float flicker = 0.5f + 0.5f * (float)Math.sin(age * 12f + flickerOffset);
        int alpha = (int)(255 * (1f - age / lifetime) * flicker);
        return FastColor.ARGB32.color(alpha, 120 + (int)(flicker * 80), 0, 180 + (int)(flicker * 75));
    }
}
class CustomImageCycleButton extends Button {
    private int currentMode;

    public CustomImageCycleButton(int x, int y, int width, int height, OnPress onPress, int initial) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.currentMode = initial;
    }

    public void setMode(int mode) {
        this.currentMode = mode;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ResourceLocation texture = getTexture(currentMode, isHovered);
        graphics.blit(texture, getX(), getY(), 0, 0, width, height, width, height);
        if (isHovered) {
            graphics.renderTooltip(Minecraft.getInstance().font,
                    Component.translatable(TransferMode.getTranslationKey(currentMode)), mouseX, mouseY);
        }
    }

    private ResourceLocation getTexture(int mode, boolean hover) {
        return switch (mode) {
            case TransferMode.INPUT_ONLY -> hover ? getInputHoverTexture() : getInputTexture();
            case TransferMode.OUTPUT_ONLY -> hover ? getOutputHoverTexture() : getOutputTexture();
            default -> hover ? getBidirectionalHoverTexture() : getBidirectionalTexture();
        };
    }

    private ResourceLocation getInputTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/transport_input_alt.png" : "textures/gui/transport_input.png");
    }

    private ResourceLocation getInputHoverTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/transport_input_hover_alt.png" : "textures/gui/transport_input_hover.png");
    }

    private ResourceLocation getOutputTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/transport_output_alt.png" : "textures/gui/transport_output.png");
    }

    private ResourceLocation getOutputHoverTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/transport_output_hover_alt.png" : "textures/gui/transport_output_hover.png");
    }

    private ResourceLocation getBidirectionalTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/transport_bidirectional_alt.png" : "textures/gui/transport_bidirectional.png");
    }

    private ResourceLocation getBidirectionalHoverTexture() {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID,
                useAltTheme ? "textures/gui/transport_bidirectional_hover_alt.png" : "textures/gui/transport_bidirectional_hover.png");
    }

}
