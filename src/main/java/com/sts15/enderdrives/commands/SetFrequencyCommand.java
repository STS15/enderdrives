package com.sts15.enderdrives.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sts15.enderdrives.items.AbstractEnderDiskItem;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * /enderdrives setfreq
 */
public final class SetFrequencyCommand {

    private SetFrequencyCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal("setfreq")
                .then(Commands.argument("frequency", IntegerArgumentType.integer(0, 4095))
                        .executes(ctx -> {
                            int freq = IntegerArgumentType.getInteger(ctx, "frequency");
                            CommandSourceStack source = ctx.getSource();
                            if (!CommandUtils.validateFrequency(freq, source)) return 0;

                            ItemStack heldItem = source.getPlayerOrException().getMainHandItem();
                            if (heldItem.getItem() instanceof AbstractEnderDiskItem) {
                                AbstractEnderDiskItem.setFrequency(heldItem, freq);
                                source.sendSuccess(() -> Component.translatable("commands.enderdrives.setfreq.success", freq), true);
                            } else {
                                source.sendFailure(Component.translatable("commands.enderdrives.setfreq.hold_disk"));
                            }
                            return 1;
                        }));
    }
}
