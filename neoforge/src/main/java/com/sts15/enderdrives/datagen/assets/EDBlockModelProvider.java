package com.sts15.enderdrives.datagen.assets;

import net.neoforged.neoforge.client.model.generators.BlockModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class EDBlockModelProvider extends BlockModelProvider {

    public EDBlockModelProvider(PackOutput output, ExistingFileHelper existingFileHelper) {
        super(output, MOD_ID, existingFileHelper);
    }

    @Override
    protected void registerModels() {
        driveCell("ender_cell");
        driveCell("tape_cell");
    }

    private void driveCell(String type) {
        getBuilder(String.format("block/drive/%s", type))
                .texture("particle", modLoc(String.format("block/drive/%s", type)))
                .texture("cell", modLoc(String.format("block/drive/%s", type)))
                .element()
                .from(0,0,0)
                .to(6,2,2)
                .rotation()
                .angle(0)
                .axis(Direction.Axis.Y)
                .origin(9,8,8)
                .end()
                .face(Direction.NORTH).uvs(0,0,6,2).texture("#cell").cullface(Direction.NORTH).end()
                .face(Direction.UP).uvs(6,0,0,2).texture("#cell").cullface(Direction.NORTH).end()
                .face(Direction.DOWN).uvs(6,0,0,2).texture("#cell").cullface(Direction.NORTH).end()
                .end();

    }
}
