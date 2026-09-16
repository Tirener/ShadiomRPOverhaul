package com.tirener.shadiom.shadiomrpoverhaul.network.names;

import com.tirener.shadiom.shadiomrpoverhaul.client.names.NamePickerScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: force-open (or refresh, on a rejected submit) the name picker with this
 * player's current candidate lists.
 */
public record OpenNamePickerS2CPacket(List<String> firstNames, List<String> surnames, String error) {

    public static void encode(OpenNamePickerS2CPacket pkt, FriendlyByteBuf buf) {
        writeStringList(buf, pkt.firstNames());
        writeStringList(buf, pkt.surnames());
        buf.writeUtf(pkt.error());
    }

    public static OpenNamePickerS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenNamePickerS2CPacket(readStringList(buf), readStringList(buf), buf.readUtf());
    }

    public static void handle(OpenNamePickerS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> openScreen(pkt)));
        ctx.get().setPacketHandled(true);
    }

    private static void openScreen(OpenNamePickerS2CPacket pkt) {
        Minecraft.getInstance().setScreen(
                new NamePickerScreen(pkt.firstNames(), pkt.surnames(), pkt.error()));
    }

    private static void writeStringList(FriendlyByteBuf buf, List<String> list) {
        buf.writeVarInt(list.size());
        for (String s : list) buf.writeUtf(s);
    }

    private static List<String> readStringList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<String> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) list.add(buf.readUtf());
        return list;
    }
}
