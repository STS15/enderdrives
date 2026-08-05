package com.sts15.enderdrives.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.db.EnderDBManager;
import com.sts15.enderdrives.db.EnderFluidDBManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /enderdrives stats
 */
public final class StatsCommand {

    private StatsCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("stats")
                .executes(ctx -> {
                    CommandSourceStack source = ctx.getSource();
                    source.sendSuccess(() -> Component.translatable(
                            "commands.enderdrives.stats.items",
                            EnderDBManager.getDatabaseSize(),
                            EnderDBManager.getTotalItemsWritten(),
                            EnderDBManager.getTotalCommits(),
                            EnderDBManager.getDatabaseFileSizeBytes()
                    ), false);

                    source.sendSuccess(() -> Component.translatable(
                            "commands.enderdrives.stats.fluids",
                            EnderFluidDBManager.getDatabaseSize(),
                            EnderFluidDBManager.getTotalRecordsWritten().get(),
                            EnderFluidDBManager.getTotalDbCommits().get(),
                            EnderFluidDBManager.getDatabaseFileSizeBytes()
                    ), false);
                    return 1;
                });
    }
}
