package com.sts15.enderdrives.datagen.assets;

import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ModelTemplate;
import net.minecraft.client.data.models.model.TextureMapping;
import net.minecraft.client.data.models.model.TextureSlot;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.model.generators.template.ExtendedModelTemplateBuilder;

import java.util.stream.Stream;

import static com.sts15.enderdrives.Constants.MOD_ID;

public final class EDBlockModelProvider extends ModelProvider {
    private static final TextureSlot CELL = TextureSlot.create("cell");
    private static final ModelTemplate DRIVE_CELL = ExtendedModelTemplateBuilder.builder()
            .requiredTextureSlot(TextureSlot.PARTICLE)
            .requiredTextureSlot(CELL)
            .element(element -> element
                    .from(0, 0, 0)
                    .to(6, 2, 2)
                    .rotation(rotation -> rotation.origin(9, 8, 8).singleAxis(Direction.Axis.Y, 0))
                    .face(Direction.NORTH, face -> face.uvs(0, 0, 6, 2).texture(CELL).cullface(Direction.NORTH))
                    .face(Direction.UP, face -> face.uvs(6, 0, 0, 2).texture(CELL).cullface(Direction.NORTH))
                    .face(Direction.DOWN, face -> face.uvs(6, 0, 0, 2).texture(CELL).cullface(Direction.NORTH)))
            .build();

    public EDBlockModelProvider(PackOutput output) {
        super(output, MOD_ID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        driveCell(blockModels, "ender_cell");
        driveCell(blockModels, "ender_fluid_cell");
        driveCell(blockModels, "tape_cell");
    }

    private static void driveCell(BlockModelGenerators blockModels, String type) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "block/drive/" + type);
        Material texture = new Material(id);
        DRIVE_CELL.create(
                id,
                new TextureMapping().put(TextureSlot.PARTICLE, texture).put(CELL, texture),
                blockModels.modelOutput
        );
    }

    @Override
    protected Stream<? extends Holder<Block>> getKnownBlocks() {
        return Stream.empty();
    }

    @Override
    protected Stream<? extends Holder<Item>> getKnownItems() {
        return Stream.empty();
    }

    @Override
    public String getName() {
        return "Ender Drives Cell Models";
    }
}
