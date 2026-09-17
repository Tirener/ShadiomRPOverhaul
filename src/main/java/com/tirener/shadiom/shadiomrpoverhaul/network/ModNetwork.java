package com.tirener.shadiom.shadiomrpoverhaul.network;

import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionClaimsSyncS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenTerritoryScreenS2CPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.TerritoryHudS2CPacket;
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
    private static final int ID_OPEN_FACTION_SCREEN = 4;
    private static final int ID_FACTION_ACTION = 5;
    private static final int ID_FACTION_CLAIMS_SYNC = 6;
    private static final int ID_OPEN_TERRITORY_SCREEN = 7;
    private static final int ID_TERRITORY_HUD_SYNC = 8;

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
        CHANNEL.registerMessage(
                ID_OPEN_FACTION_SCREEN,
                OpenFactionScreenS2CPacket.class,
                OpenFactionScreenS2CPacket::encode,
                OpenFactionScreenS2CPacket::decode,
                OpenFactionScreenS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                ID_FACTION_ACTION,
                FactionActionC2SPacket.class,
                FactionActionC2SPacket::encode,
                FactionActionC2SPacket::decode,
                FactionActionC2SPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
        CHANNEL.registerMessage(
                ID_FACTION_CLAIMS_SYNC,
                FactionClaimsSyncS2CPacket.class,
                FactionClaimsSyncS2CPacket::encode,
                FactionClaimsSyncS2CPacket::decode,
                FactionClaimsSyncS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                ID_OPEN_TERRITORY_SCREEN,
                OpenTerritoryScreenS2CPacket.class,
                OpenTerritoryScreenS2CPacket::encode,
                OpenTerritoryScreenS2CPacket::decode,
                OpenTerritoryScreenS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        CHANNEL.registerMessage(
                ID_TERRITORY_HUD_SYNC,
                TerritoryHudS2CPacket.class,
                TerritoryHudS2CPacket::encode,
                TerritoryHudS2CPacket::decode,
                TerritoryHudS2CPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }
}
