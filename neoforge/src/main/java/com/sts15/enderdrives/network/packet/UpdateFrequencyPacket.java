package com.sts15.enderdrives.network.packet;

import com.sts15.enderdrives.items.EnderDiskItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import static com.sts15.enderdrives.Constants.MOD_ID;

public class UpdateFrequencyPacket implements CustomPacketPayload {
    private final int frequency;
    private final int scope;

    public UpdateFrequencyPacket(int frequency, int scope) {
        this.frequency = frequency;
        this.scope = scope;
    }

    public static final Type<UpdateFrequencyPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "update_frequency"));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static final StreamCodec<FriendlyByteBuf, UpdateFrequencyPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UpdateFrequencyPacket::getFrequency,
            ByteBufCodecs.VAR_INT, UpdateFrequencyPacket::getScope,
            UpdateFrequencyPacket::new
    );

    private int getFrequency() { return frequency; }
    private int getScope() { return scope; }

    public static void handle(UpdateFrequencyPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                ItemStack heldItem = player.getMainHandItem();
                if (heldItem.getItem() instanceof EnderDiskItem) {
                    EnderDiskItem.setFrequency(heldItem, packet.frequency);
                    EnderDiskItem.setScope(heldItem, packet.scope);
                    if (packet.scope == 1) {
                        EnderDiskItem.setOwnerUUID(heldItem, player.getUUID());
                    }

                }
            }
        });
    }
}
