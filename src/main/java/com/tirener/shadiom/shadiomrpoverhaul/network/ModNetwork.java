package com.tirener.shadiom.shadiomrpoverhaul.network;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.network.names.NameSyncPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.names.OpenNamePickerS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.names.SubmitNamePickC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.title.TitleSyncPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;

public class ModNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Shadiomrpoverhaul.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    // Never renumber these - see ModNetwork history for why (shifts every later id).
    private static final int ID_TITLE_SYNC = 0;
    private static final int ID_OPEN_NAME_PICKER = 1;
    private static final int ID_SUBMIT_NAME_PICK = 2;
    private static final int ID_NAME_SYNC = 3;

    public static void register() {
        CHANNEL.registerMessage(
                ID_TITLE_SYNC,
                TitleSyncPacket.class,
                TitleSyncPacket::encode,
                TitleSyncPacket::decode,
                TitleSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                ID_OPEN_NAME_PICKER,
                OpenNamePickerS2CPacket.class,
                OpenNamePickerS2CPacket::encode,
                OpenNamePickerS2CPacket::decode,
                OpenNamePickerS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                ID_SUBMIT_NAME_PICK,
                SubmitNamePickC2SPacket.class,
                SubmitNamePickC2SPacket::encode,
                SubmitNamePickC2SPacket::decode,
                SubmitNamePickC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                ID_NAME_SYNC,
                NameSyncPacket.class,
                NameSyncPacket::encode,
                NameSyncPacket::decode,
                NameSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }
}
