package com.sts15.enderdrives.commands;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class ModCommands {

    private ModCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("enderdrives")
                        .then(SetFrequencyCommand.register())
                        .then(ClearCommand.register())
                        .requires(source -> source.hasPermission(4))
                        .then(StatsCommand.register())
                        .then(AutoBenchmarkCommand.register())
                        .then(StressTestCommand.register())
                        .then(DumpCellCommand.register())
                        .then(TapeCommand.register())
        );
    }
}
