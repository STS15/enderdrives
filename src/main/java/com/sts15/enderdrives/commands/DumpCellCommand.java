package com.sts15.enderdrives.commands;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import appeng.me.cells.BasicCellInventory;
import appeng.me.helpers.BaseActionSource;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.db.AEKeyCacheEntry;
import com.sts15.enderdrives.db.EnderDBManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * /enderdrives dumpcell
 */
public final class DumpCellCommand {

    private DumpCellCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("dumpcell")
                .then(Commands.argument("type", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            builder.suggest("private");
                            builder.suggest("team");
                            builder.suggest("global");
                            return builder.buildFuture();
                        })
                        .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                                .executes(ctx -> {
                                    CommandSourceStack source = ctx.getSource();
                                    ServerPlayer player = source.getPlayerOrException();
                                    int frequency = IntegerArgumentType.getInteger(ctx, "frequency");
                                    if (!CommandUtils.validateFrequency(frequency, source)) return 0;

                                    String type = StringArgumentType.getString(ctx, "type");
                                    Optional<String> scopePrefix = CommandUtils.resolveScopePrefix(
                                            source,
                                            player,
                                            type,
                                            "commands.enderdrives.dumpcell.no_permission"
                                    );
                                    if (scopePrefix.isEmpty()) return 0;

                                    List<AEKeyCacheEntry> entries = EnderDBManager.queryItemsByFrequency(scopePrefix.get(), frequency);

                                    if (entries.isEmpty()) {
                                        source.sendFailure(Component.translatable("commands.enderdrives.dumpcell.no_items", frequency));
                                        return 0;
                                    }

                                    List<ItemStack> createdCells = new ArrayList<>();
                                    ItemStack currentCell = null;
                                    BasicCellInventory handler = null;

                                    long totalInserted = 0;

                                    for (AEKeyCacheEntry entry : entries) {
                                        AEItemKey key = entry.aeKey();
                                        long remaining = entry.count();

                                        while (remaining > 0) {
                                            if (handler == null) {
                                                currentCell = new ItemStack(AEItems.ITEM_CELL_256K.get());
                                                var inventory = StorageCells.getCellInventory(currentCell, null);

                                                if (!(inventory instanceof BasicCellInventory h)) {
                                                    source.sendFailure(Component.translatable("commands.enderdrives.dumpcell.cell_access_failed"));
                                                    return 0;
                                                }

                                                handler = h;
                                                createdCells.add(currentCell);
                                                int currentDriveIndex = createdCells.size();
                                                String prettyType = type.substring(0, 1).toUpperCase() + type.substring(1);
                                                Component customName = Component.literal("EnderDrives " + prettyType + ":" + frequency + " Drive:" + currentDriveIndex);
                                                currentCell.set(DataComponents.CUSTOM_NAME, customName);

                                            }

                                            long insertAmount = Math.min(remaining, Integer.MAX_VALUE);
                                            long inserted = handler.insert(key, insertAmount, Actionable.MODULATE, new BaseActionSource());

                                            if (inserted <= 0) {
                                                handler = null;
                                                continue;
                                            }

                                            remaining -= inserted;
                                            totalInserted += inserted;
                                        }
                                    }


                                    for (ItemStack c : createdCells) {
                                        player.getInventory().placeItemBackInInventory(c);
                                    }

                                    long finalTotalInserted = totalInserted;
                                    source.sendSuccess(() -> Component.translatable("commands.enderdrives.dumpcell.success", finalTotalInserted, createdCells.size()), true);
                                    source.sendSuccess(() -> Component.translatable("commands.enderdrives.dumpcell.success_clear_hint"), true);
                                    return 1;
                                })
                        )
                );
    }
}
