package com.apocscode.mcai.network;

import com.apocscode.mcai.MCAi;
import com.apocscode.mcai.entity.CompanionEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → Server: Whistle to call/teleport the companion to the player.
 * If companion is within 64 blocks, pathfind. If farther, teleport directly.
 */
public record WhistleCompanionPacket() implements CustomPacketPayload {
    public static final Type<WhistleCompanionPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MCAi.MOD_ID, "whistle_companion"));

    public static final StreamCodec<ByteBuf, WhistleCompanionPacket> STREAM_CODEC =
            StreamCodec.unit(new WhistleCompanionPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(WhistleCompanionPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            CompanionEntity companion = CompanionEntity.getLivingCompanion(player.getUUID());
            if (companion == null) {
                player.sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("§cNo companion found!"));
                return;
            }

            double dist = companion.distanceTo(player);
            if (dist > 64.0) {
                // Teleport directly
                companion.teleportTo(player.getX(), player.getY(), player.getZ());
                companion.getNavigation().stop();
                companion.getChat().say(
                        com.apocscode.mcai.entity.CompanionChat.Category.GREETING,
                        "Coming! *teleports*");
            } else if (dist > 6.0) {
                // Pathfind to player
                companion.getNavigation().moveTo(player.getX(), player.getY(), player.getZ(), 1.4);
                companion.getChat().say(
                        com.apocscode.mcai.entity.CompanionChat.Category.GREETING,
                        "On my way!");
            }
            // If already close, do nothing
        });
    }
}
