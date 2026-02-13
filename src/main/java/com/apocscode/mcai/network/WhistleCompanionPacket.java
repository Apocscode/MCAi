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

            // Cancel any active task — the player is explicitly calling the companion back
            if (!companion.getTaskManager().isIdle()) {
                companion.getTaskManager().cancelAll();
                companion.getChat().say(
                        com.apocscode.mcai.entity.CompanionChat.Category.TASK,
                        "Abandoning current task — you called?");
            }

            double dist = companion.distanceTo(player);
            if (dist > 16.0) {
                // Teleport to safe position near the player
                net.minecraft.core.BlockPos ownerPos = player.blockPosition();
                net.minecraft.core.BlockPos safePos = findSafeTeleportPos(
                        companion, ownerPos, 4);
                if (safePos != null) {
                    companion.moveTo(safePos.getX() + 0.5, safePos.getY(),
                            safePos.getZ() + 0.5, companion.getYRot(), companion.getXRot());
                } else {
                    companion.moveTo(player.getX(), player.getY(), player.getZ(),
                            companion.getYRot(), companion.getXRot());
                }
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

    /**
     * Find a safe teleport position near a target BlockPos.
     * Checks horizontally around the target and vertically for solid ground.
     */
    private static net.minecraft.core.BlockPos findSafeTeleportPos(
            CompanionEntity companion, net.minecraft.core.BlockPos target, int range) {
        net.minecraft.world.level.Level level = companion.level();
        if (com.apocscode.mcai.task.BlockHelper.isSafeToStand(level, target)) return target;

        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                if (dx == 0 && dz == 0) continue;
                net.minecraft.core.BlockPos candidate = target.offset(dx, 0, dz);
                for (int dy = 0; dy <= 5; dy++) {
                    if (com.apocscode.mcai.task.BlockHelper.isSafeToStand(level, candidate.below(dy))) {
                        return candidate.below(dy);
                    }
                    if (dy > 0 && com.apocscode.mcai.task.BlockHelper.isSafeToStand(level, candidate.above(dy))) {
                        return candidate.above(dy);
                    }
                }
            }
        }
        return null;
    }
}
