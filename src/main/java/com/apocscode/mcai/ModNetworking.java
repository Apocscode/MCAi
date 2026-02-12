package com.apocscode.mcai;

import com.apocscode.mcai.network.ChatMessagePacket;
import com.apocscode.mcai.network.ChatResponsePacket;
import com.apocscode.mcai.network.OpenChatScreenPacket;
import com.apocscode.mcai.network.CycleWandModePacket;
import com.apocscode.mcai.network.SetBehaviorModePacket;
import com.apocscode.mcai.network.SyncHomeAreaPacket;
import com.apocscode.mcai.network.SyncTaggedBlocksPacket;
import com.apocscode.mcai.network.SyncWandModePacket;
import com.apocscode.mcai.network.WhistleCompanionPacket;
import com.apocscode.mcai.network.StopInteractingPacket;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = MCAi.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class ModNetworking {

    public static void init(IEventBus bus) {
        // Registration happens via event subscriber below
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(MCAi.MOD_ID).versioned("1.0");

        // Server → Client: open chat screen
        registrar.playToClient(
                OpenChatScreenPacket.TYPE,
                OpenChatScreenPacket.STREAM_CODEC,
                OpenChatScreenPacket::handle);

        // Client → Server: player chat message
        registrar.playToServer(
                ChatMessagePacket.TYPE,
                ChatMessagePacket.STREAM_CODEC,
                ChatMessagePacket::handle);

        // Server → Client: AI response
        registrar.playToClient(
                ChatResponsePacket.TYPE,
                ChatResponsePacket.STREAM_CODEC,
                ChatResponsePacket::handle);

        // Client → Server: set companion behavior mode
        registrar.playToServer(
                SetBehaviorModePacket.TYPE,
                SetBehaviorModePacket.STREAM_CODEC,
                SetBehaviorModePacket::handle);

        // Client → Server: cycle logistics wand mode
        registrar.playToServer(
                CycleWandModePacket.TYPE,
                CycleWandModePacket.STREAM_CODEC,
                CycleWandModePacket::handle);

        // Server → Client: sync tagged logistics blocks
        registrar.playToClient(
                SyncTaggedBlocksPacket.TYPE,
                SyncTaggedBlocksPacket.STREAM_CODEC,
                SyncTaggedBlocksPacket::handle);

        // Server → Client: sync wand mode
        registrar.playToClient(
                SyncWandModePacket.TYPE,
                SyncWandModePacket.STREAM_CODEC,
                SyncWandModePacket::handle);

        // Server → Client: sync home area corners for outline rendering
        registrar.playToClient(
                SyncHomeAreaPacket.TYPE,
                SyncHomeAreaPacket.STREAM_CODEC,
                SyncHomeAreaPacket::handle);

        // Client → Server: whistle to call companion
        registrar.playToServer(
                WhistleCompanionPacket.TYPE,
                WhistleCompanionPacket.STREAM_CODEC,
                WhistleCompanionPacket::handle);

        // Client → Server: owner done interacting (unfreeze companion)
        registrar.playToServer(
                StopInteractingPacket.TYPE,
                StopInteractingPacket.STREAM_CODEC,
                StopInteractingPacket::handle);
    }
}
