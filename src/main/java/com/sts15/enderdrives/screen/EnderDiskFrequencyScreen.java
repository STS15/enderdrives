package com.sts15.enderdrives.screen;

import com.sts15.enderdrives.client.ClientConfigCache;
import com.sts15.enderdrives.network.ClientNetworkHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.blockentity.AbstractEndPortalRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.DyeColor;
import net.neoforged.fml.ModList;

import static com.sts15.enderdrives.Constants.MOD_ID;
import static com.sts15.enderdrives.screen.EnderDiskFrequencyScreen.useAltTheme;

public class EnderDiskFrequencyScreen extends Screen {
    private static final Component TITLE = Component.translatable("screen.enderdrives.frequency_selector");
    public static final int WINDOW_WIDTH = 176;
    public static final int WINDOW_HEIGHT = 105;
    private static final int[] SELECTOR_X = {33, 83, 133};
    private static final int SELECTOR_Y = 37;
    private static final int ARROW_WIDTH = 8;
    private static final int ARROW_HEIGHT = 11;
    private static final int ARROW_SPRITE_SIZE = 16;
    private static final int[] ARROW_TOP_X = {30, 80, 130};
    private static final int ARROW_TOP_Y = 18;
    private static final int[] ARROW_BOTTOM_X = ARROW_TOP_X;
    private static final int ARROW_BOTTOM_Y = 63;
    private static final float PARTICLE_SPAWN_INTERVAL = 0.05F;
    private static final int MAX_UI_PARTICLES = 256;

    public static boolean useAltTheme = true;

    private int leftPos;
    private int topPos;
    private EditBox frequencyField;
    private final int[] dyeIndices = new int[3];
    private int frequency;
    FrequencyScope currentScope;
    private final boolean ftbTeamsLoaded = ModList.get().isLoaded("ftbteams");
    private final List<Particle2D> uiParticles = new ArrayList<>();
    private float particleSpawnTimer;
    private final Random random = new Random();
    private int transferMode = TransferMode.BIDIRECTIONAL;
    private final InteractionHand hand;
    private final int initialFrequency;
    private final FrequencyScope initialScope;
    private final int initialTransferMode;
    private final Identifier expectedItemId;
    private final int expectedStackHash;
    private CustomImageCycleButton transferButton;

    public EnderDiskFrequencyScreen(int currentFrequency, FrequencyScope scope, int transferMode) {
        this(currentFrequency, scope, transferMode, InteractionHand.MAIN_HAND,
                Identifier.fromNamespaceAndPath(MOD_ID, "invalid"), 0);
    }

    public EnderDiskFrequencyScreen(
            int currentFrequency,
            FrequencyScope scope,
            int transferMode,
            InteractionHand hand,
            Identifier expectedItemId,
            int expectedStackHash
    ) {
        super(Component.translatable("screen.enderdrives.frequency"));
        this.frequency = clampFrequency(currentFrequency);
        this.currentScope = scope;
        this.transferMode = transferMode;
        this.hand = hand;
        this.initialFrequency = currentFrequency;
        this.initialScope = scope;
        this.initialTransferMode = transferMode;
        this.expectedItemId = expectedItemId;
        this.expectedStackHash = expectedStackHash;
        decodeFrequency();
    }

    static int textColor() {
        return ARGB.opaque(useAltTheme ? 0xFFE44C : 0xFFFFFF);
    }

    private Identifier getEnderDiskMask() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/ender_disk_mask_alt.png" : "textures/gui/ender_disk_mask.png"
        );
    }

    private Identifier getArrowUpHover() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/up_arrow_highlight_alt.png" : "textures/gui/up_arrow_highlight.png"
        );
    }

    private Identifier getArrowDownHover() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/down_arrow_highlight_alt.png" : "textures/gui/down_arrow_highlight.png"
        );
    }

    private Identifier getButtonTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/scope_button_alt.png" : "textures/gui/scope_button.png"
        );
    }

    private Identifier getButtonHoverTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/scope_button_hover_alt.png" : "textures/gui/scope_button_hover.png"
        );
    }

    @Override
    protected void init() {
        if (!currentScope.isEnabled() || currentScope == FrequencyScope.TEAM && !ftbTeamsLoaded) {
            currentScope = FrequencyScope.getDefault();
            if (currentScope == FrequencyScope.TEAM && !ftbTeamsLoaded) {
                currentScope = FrequencyScope.getEnabledScopes().stream()
                        .filter(scope -> scope != FrequencyScope.TEAM)
                        .findFirst()
                        .orElse(FrequencyScope.GLOBAL);
            }
        }

        frequency = clampFrequency(frequency);
        decodeFrequency();
        leftPos = (width - WINDOW_WIDTH) / 2;
        topPos = (height - WINDOW_HEIGHT) / 2;

        frequencyField = new EditBox(
                Minecraft.getInstance().font,
                leftPos + 9,
                topPos + 89,
                70,
                12,
                Component.translatable("screen.enderdrives.frequency_placeholder")
        );
        frequencyField.setMaxLength(4);
        frequencyField.setValue(String.valueOf(frequency));
        frequencyField.setResponder(this::onFrequencyFieldChanged);
        frequencyField.setBordered(false);
        frequencyField.setVisible(true);
        frequencyField.setTextColor(textColor());
        frequencyField.setTextShadow(true);
        frequencyField.setCanLoseFocus(true);
        addRenderableWidget(frequencyField);

        int transferButtonX = leftPos + (useAltTheme ? 102 : 100);
        transferButton = new CustomImageCycleButton(
                transferButtonX,
                topPos + 85,
                15,
                14,
                button -> {
                    transferMode = TransferMode.next(transferMode);
                    transferButton.setMode(transferMode);
                },
                transferMode
        );
        addRenderableWidget(transferButton);

        int scopeButtonX = leftPos + (useAltTheme ? 120 : 118);
        addRenderableWidget(new CustomImageButton(
                scopeButtonX,
                topPos + 85,
                50,
                14,
                button -> cycleScope(),
                getButtonTexture(),
                getButtonHoverTexture(),
                this
        ));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        AbstractTexture skyTexture = textureManager.getTexture(AbstractEndPortalRenderer.END_SKY_LOCATION);
        AbstractTexture portalTexture = textureManager.getTexture(AbstractEndPortalRenderer.END_PORTAL_LOCATION);
        TextureSetup textureSetup = TextureSetup.doubleTexture(
                skyTexture.getTextureView(),
                skyTexture.getSampler(),
                portalTexture.getTextureView(),
                portalTexture.getSampler()
        );

        int shaderX = leftPos + 8;
        int shaderY = topPos + 18;
        graphics.fill(RenderPipelines.END_PORTAL, textureSetup, shaderX, shaderY, shaderX + 162, shaderY + 68);
        extractColorSelectors(graphics);
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                getEnderDiskMask(),
                leftPos,
                topPos,
                0,
                0,
                176,
                105,
                256,
                256
        );
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractUiParticles(graphics);
        extractTitle(graphics);
        extractArrowButtons(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        if (frequencyField.getValue().isEmpty() && !frequencyField.isFocused()) {
            graphics.text(
                    Minecraft.getInstance().font,
                    Component.translatable("screen.enderdrives.frequency_label"),
                    frequencyField.getX() + 2,
                    frequencyField.getY() + 2,
                    textColor(),
                    false
            );
        }

        for (int i = 0; i < 3; i++) {
            int topX = leftPos + ARROW_TOP_X[i];
            int topY = topPos + ARROW_TOP_Y;
            int bottomX = leftPos + ARROW_BOTTOM_X[i];
            int bottomY = topPos + ARROW_BOTTOM_Y;
            if (isHovering(mouseX, mouseY, topX, topY, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE)) {
                graphics.setTooltipForNextFrame(font, Component.translatable(getTooltipText(i, true)), mouseX, mouseY);
                return;
            }
            if (isHovering(mouseX, mouseY, bottomX, bottomY, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE)) {
                graphics.setTooltipForNextFrame(font, Component.translatable(getTooltipText(i, false)), mouseX, mouseY);
                return;
            }
        }
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private void cycleScope() {
        FrequencyScope[] scopes = FrequencyScope.values();
        for (int offset = 1; offset <= scopes.length; offset++) {
            FrequencyScope candidate = scopes[(currentScope.ordinal() + offset) % scopes.length];
            if (candidate == FrequencyScope.TEAM && !ftbTeamsLoaded) continue;
            if (!candidate.isEnabled()) continue;
            currentScope = candidate;
            return;
        }
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

    private void extractUiParticles(GuiGraphicsExtractor graphics) {
        float deltaTime = Math.min(Minecraft.getInstance().getFrameTimeNs() / 1_000_000_000F, 0.1F);
        particleSpawnTimer += deltaTime;
        while (particleSpawnTimer >= PARTICLE_SPAWN_INTERVAL) {
            if (uiParticles.size() < MAX_UI_PARTICLES) {
                spawnParticle();
            }
            particleSpawnTimer -= PARTICLE_SPAWN_INTERVAL;
        }

        for (Particle2D particle : uiParticles) {
            particle.update(deltaTime);
        }

        int menuRight = leftPos + WINDOW_WIDTH;
        int menuBottom = topPos + WINDOW_HEIGHT;
        uiParticles.removeIf(particle -> !particle.isAlive(leftPos, topPos, menuRight, menuBottom));
        for (Particle2D particle : uiParticles) {
            int size = Math.max(1, (int) particle.size);
            graphics.fill((int) particle.x, (int) particle.y, (int) particle.x + size, (int) particle.y + size, particle.getColor());
        }
    }

    private void spawnParticle() {
        int centerX = leftPos + WINDOW_WIDTH / 2;
        int centerY = topPos + WINDOW_HEIGHT / 2;
        int maxScreenX = Math.max(0, width - 1);
        int maxScreenY = Math.max(0, height - 1);
        int margin = 20;
        int minX = clamp(leftPos - margin, 0, maxScreenX);
        int maxX = clamp(leftPos + WINDOW_WIDTH + margin, 0, maxScreenX);
        int minY = clamp(topPos, 0, maxScreenY);
        int maxY = clamp(topPos + WINDOW_HEIGHT, 0, maxScreenY);
        int x;
        int y;

        switch (random.nextInt(4)) {
            case 0 -> {
                x = randomBetween(minX, maxX);
                y = clamp(topPos - margin, 0, maxScreenY);
            }
            case 1 -> {
                x = randomBetween(minX, maxX);
                y = clamp(topPos + WINDOW_HEIGHT + margin, 0, maxScreenY);
            }
            case 2 -> {
                x = clamp(leftPos - margin, 0, maxScreenX);
                y = randomBetween(minY, maxY);
            }
            default -> {
                x = clamp(leftPos + WINDOW_WIDTH + margin, 0, maxScreenX);
                y = randomBetween(minY, maxY);
            }
        }

        float dx = centerX - x;
        float dy = centerY - y;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance < 0.001F) {
            return;
        }

        float speed = 40.0F + random.nextFloat() * 20.0F;
        uiParticles.add(new Particle2D(x, y, dx / distance * speed, dy / distance * speed, distance / speed));
    }

    private int randomBetween(int min, int max) {
        return max <= min ? min : min + random.nextInt(max - min + 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int clampFrequency(int value) {
        int min = Math.min(ClientConfigCache.freqMin, ClientConfigCache.freqMax);
        int max = Math.max(ClientConfigCache.freqMin, ClientConfigCache.freqMax);
        return clamp(value, min, max);
    }

    private void extractColorSelectors(GuiGraphicsExtractor graphics) {
        for (int i = 0; i < 3; i++) {
            int x = leftPos + SELECTOR_X[i] - 3;
            int y = topPos + SELECTOR_Y - 2;
            DyeColor dye = DyeColor.byId(dyeIndices[i]);
            fillRoundedRect(graphics, x, y, 16, 30, 3, ARGB.color(255, dye.getTextColor()));
        }
    }

    private void fillRoundedRect(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
        graphics.fill(x + radius, y, x + width - radius, y + height, color);
        graphics.fill(x, y + radius, x + radius, y + height - radius, color);
        graphics.fill(x + width - radius, y + radius, x + width, y + height - radius, color);
        fillCircle(graphics, x + radius, y + radius, radius, color);
        fillCircle(graphics, x + width - radius - 1, y + radius, radius, color);
        fillCircle(graphics, x + radius, y + height - radius - 1, radius, color);
        fillCircle(graphics, x + width - radius - 1, y + height - radius - 1, radius, color);
    }

    private void fillCircle(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int color) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                if (dx * dx + dy * dy <= radius * radius) {
                    graphics.fill(centerX + dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, color);
                }
            }
        }
    }

    private void extractTitle(GuiGraphicsExtractor graphics) {
        int titleX = leftPos + 12;
        int titleY = topPos + (useAltTheme ? 6 : 4);
        graphics.text(Minecraft.getInstance().font, TITLE, titleX, titleY, textColor(), false);
    }

    private void extractArrowButtons(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (int i = 0; i < 3; i++) {
            int topX = leftPos + ARROW_TOP_X[i];
            int topY = topPos + ARROW_TOP_Y;
            int bottomX = leftPos + ARROW_BOTTOM_X[i];
            int bottomY = topPos + ARROW_BOTTOM_Y + 1;
            if (isHovering(mouseX, mouseY, topX + 4, topY + 2, ARROW_WIDTH, ARROW_HEIGHT)) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        getArrowUpHover(),
                        topX,
                        topY,
                        0,
                        0,
                        ARROW_SPRITE_SIZE,
                        ARROW_SPRITE_SIZE,
                        ARROW_SPRITE_SIZE,
                        ARROW_SPRITE_SIZE
                );
            }
            if (isHovering(mouseX, mouseY, bottomX + 4, bottomY + 2, ARROW_WIDTH, ARROW_HEIGHT)) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        getArrowDownHover(),
                        bottomX,
                        bottomY,
                        0,
                        0,
                        ARROW_SPRITE_SIZE,
                        ARROW_SPRITE_SIZE,
                        ARROW_SPRITE_SIZE,
                        ARROW_SPRITE_SIZE
                );
            }
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0) {
            int mouseX = (int) event.x();
            int mouseY = (int) event.y();
            for (int i = 0; i < 3; i++) {
                int topX = leftPos + ARROW_TOP_X[i];
                int topY = topPos + ARROW_TOP_Y;
                int bottomX = leftPos + ARROW_BOTTOM_X[i];
                int bottomY = topPos + ARROW_BOTTOM_Y;
                if (isHovering(mouseX, mouseY, topX, topY, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE)) {
                    dyeIndices[i] = (dyeIndices[i] + 1) % 16;
                    updateFrequency();
                    return true;
                }
                if (isHovering(mouseX, mouseY, bottomX, bottomY, ARROW_SPRITE_SIZE, ARROW_SPRITE_SIZE)) {
                    dyeIndices[i] = (dyeIndices[i] - 1 + 16) % 16;
                    updateFrequency();
                    return true;
                }
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private boolean isHovering(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void decodeFrequency() {
        dyeIndices[0] = frequency >> 8 & 0xF;
        dyeIndices[1] = frequency >> 4 & 0xF;
        dyeIndices[2] = frequency & 0xF;
    }

    private void updateFrequency() {
        frequency = clampFrequency(dyeIndices[0] << 8 | dyeIndices[1] << 4 | dyeIndices[2]);
        decodeFrequency();
        if (frequencyField != null) {
            frequencyField.setValue(String.valueOf(frequency));
        }
    }

    private void onFrequencyFieldChanged(String text) {
        try {
            int value = Integer.parseInt(text);
            if (value < Math.min(ClientConfigCache.freqMin, ClientConfigCache.freqMax)
                    || value > Math.max(ClientConfigCache.freqMin, ClientConfigCache.freqMax)) {
                return;
            }
            frequency = value;
            decodeFrequency();
        } catch (NumberFormatException ignored) {
        }
    }

    @Override
    public void onClose() {
        super.onClose();
        int safeFrequency = clampFrequency(frequency);
        ClientNetworkHandler.sendFrequencyUpdateToServer(
                safeFrequency,
                currentScope,
                transferMode,
                hand,
                initialFrequency,
                initialScope,
                initialTransferMode,
                expectedItemId,
                expectedStackHash
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open(Optional<Integer> currentFrequency, FrequencyScope scope, int transferMode) {
        open(currentFrequency.orElse(ClientConfigCache.freqMin), scope, transferMode, InteractionHand.MAIN_HAND);
    }

    public static void open(int currentFrequency, FrequencyScope scope, int transferMode) {
        open(currentFrequency, scope, transferMode, InteractionHand.MAIN_HAND);
    }

    public static void open(int currentFrequency, FrequencyScope scope, int transferMode, InteractionHand hand) {
        open(currentFrequency, scope, transferMode, hand,
                Identifier.fromNamespaceAndPath(MOD_ID, "invalid"), 0);
    }

    public static void open(
            int currentFrequency,
            FrequencyScope scope,
            int transferMode,
            InteractionHand hand,
            Identifier expectedItemId,
            int expectedStackHash
    ) {
        Minecraft.getInstance().setScreen(new EnderDiskFrequencyScreen(
                currentFrequency, scope, transferMode, hand, expectedItemId, expectedStackHash));
    }
}

class CustomImageButton extends Button {
    private final Identifier normalTexture;
    private final Identifier hoverTexture;
    private final EnderDiskFrequencyScreen parent;

    CustomImageButton(
            int x,
            int y,
            int width,
            int height,
            OnPress onPress,
            Identifier normalTexture,
            Identifier hoverTexture,
            EnderDiskFrequencyScreen parent
    ) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        this.normalTexture = normalTexture;
        this.hoverTexture = hoverTexture;
        this.parent = parent;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Identifier texture = isHovered ? hoverTexture : normalTexture;
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, getX(), getY(), 0, 0, width, height, width, height);

        Component label = Component.translatable(parent.currentScope.translationKey());
        int textX = getX() + (width - Minecraft.getInstance().font.width(label)) / 2;
        int textY = getY() + (height - 8) / 2;
        graphics.text(Minecraft.getInstance().font, label, textX, textY, EnderDiskFrequencyScreen.textColor(), false);
        if (isHovered) {
            graphics.setTooltipForNextFrame(
                    Minecraft.getInstance().font,
                    Component.translatable("screen.enderdrives.toggle_scope"),
                    mouseX,
                    mouseY
            );
        }
    }
}

class Particle2D {
    float x;
    float y;
    private final float dx;
    private final float dy;
    private final float lifetime;
    private float age;
    final float size;
    private final float flickerOffset;

    Particle2D(float x, float y, float dx, float dy, float lifetime) {
        this.x = x;
        this.y = y;
        this.dx = dx;
        this.dy = dy;
        this.lifetime = lifetime;
        this.size = 2.0F + (float) Math.random() * 2.0F;
        this.flickerOffset = (float) Math.random() * (float) Math.PI * 2.0F;
    }

    void update(float delta) {
        x += dx * delta;
        y += dy * delta;
        age += delta;
    }

    boolean isAlive(int menuLeft, int menuTop, int menuRight, int menuBottom) {
        boolean insideMenu = x >= menuLeft && x <= menuRight && y >= menuTop && y <= menuBottom;
        return age < lifetime && !insideMenu;
    }

    int getColor() {
        float flicker = 0.5F + 0.5F * (float) Math.sin(age * 12.0F + flickerOffset);
        int alpha = clampColorChannel((int) (255.0F * (1.0F - age / lifetime) * flicker));
        int green = clampColorChannel(120 + (int) (flicker * 80.0F));
        int blue = clampColorChannel(180 + (int) (flicker * 75.0F));
        return ARGB.color(alpha, green, 0, blue);
    }

    private static int clampColorChannel(int value) {
        return Math.max(0, Math.min(value, 255));
    }
}

class CustomImageCycleButton extends Button {
    private int currentMode;

    CustomImageCycleButton(int x, int y, int width, int height, OnPress onPress, int initial) {
        super(x, y, width, height, Component.empty(), onPress, DEFAULT_NARRATION);
        currentMode = initial;
    }

    void setMode(int mode) {
        currentMode = mode;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Identifier texture = getTexture(currentMode, isHovered);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, getX(), getY(), 0, 0, width, height, width, height);
        if (isHovered) {
            graphics.setTooltipForNextFrame(
                    Minecraft.getInstance().font,
                    Component.translatable(TransferMode.getTranslationKey(currentMode)),
                    mouseX,
                    mouseY
            );
        }
    }

    private Identifier getTexture(int mode, boolean hover) {
        return switch (mode) {
            case TransferMode.INPUT_ONLY -> hover ? getInputHoverTexture() : getInputTexture();
            case TransferMode.OUTPUT_ONLY -> hover ? getOutputHoverTexture() : getOutputTexture();
            default -> hover ? getBidirectionalHoverTexture() : getBidirectionalTexture();
        };
    }

    private Identifier getInputTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/transport_input_alt.png" : "textures/gui/transport_input.png"
        );
    }

    private Identifier getInputHoverTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/transport_input_hover_alt.png" : "textures/gui/transport_input_hover.png"
        );
    }

    private Identifier getOutputTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/transport_output_alt.png" : "textures/gui/transport_output.png"
        );
    }

    private Identifier getOutputHoverTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID, useAltTheme ? "textures/gui/transport_output_hover_alt.png" : "textures/gui/transport_output_hover.png"
        );
    }

    private Identifier getBidirectionalTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID,
                useAltTheme ? "textures/gui/transport_bidirectional_alt.png" : "textures/gui/transport_bidirectional.png"
        );
    }

    private Identifier getBidirectionalHoverTexture() {
        return Identifier.fromNamespaceAndPath(
                MOD_ID,
                useAltTheme
                        ? "textures/gui/transport_bidirectional_hover_alt.png"
                        : "textures/gui/transport_bidirectional_hover.png"
        );
    }
}
