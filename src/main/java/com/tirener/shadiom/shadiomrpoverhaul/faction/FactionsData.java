package com.tirener.shadiom.shadiomrpoverhaul.faction;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** World-wide faction roster and pending invites. Storage only - {@link FactionEventHandler}
 *  holds every rule about who may do what; this class just persists whatever it's told, same
 *  division of responsibility as {@code NamesData} vs {@code NameEventHandler}. */
final class FactionsData extends SavedData {

    private final Map<String, Faction> factions = new HashMap<>();
    private final Map<UUID, String> memberIndex = new HashMap<>();
    private final Map<UUID, Set<String>> pendingInvites = new HashMap<>();

    Faction get(String id) { return factions.get(id); }

    boolean exists(String id) { return factions.containsKey(id); }

    Faction factionOf(UUID player) {
        String id = memberIndex.get(player);
        return id == null ? null : factions.get(id);
    }

    void put(Faction faction) {
        factions.put(faction.id(), faction);
        reindex(faction);
    }

    void remove(String id) {
        Faction faction = factions.remove(id);
        if (faction != null) {
            for (UUID member : faction.allMembers()) memberIndex.remove(member);
        }
        setDirty();
    }

    /** Recomputes this faction's entries in the reverse lookup from its current rosters. Call
     *  after any membership change so it never drifts out of sync. */
    void reindex(Faction faction) {
        memberIndex.values().removeIf(id -> id.equals(faction.id()));
        for (UUID member : faction.allMembers()) memberIndex.put(member, faction.id());
        setDirty();
    }

    Set<String> invitesOf(UUID player) {
        return pendingInvites.getOrDefault(player, Set.of());
    }

    void addInvite(UUID player, String factionId) {
        pendingInvites.computeIfAbsent(player, k -> new HashSet<>()).add(factionId);
        setDirty();
    }

    void removeInvite(UUID player, String factionId) {
        Set<String> invites = pendingInvites.get(player);
        if (invites == null) return;
        invites.remove(factionId);
        if (invites.isEmpty()) pendingInvites.remove(player);
        setDirty();
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag nbt) {
        ListTag factionList = new ListTag();
        for (Faction faction : factions.values()) factionList.add(writeFaction(faction));
        nbt.put("factions", factionList);

        CompoundTag invitesTag = new CompoundTag();
        for (Map.Entry<UUID, Set<String>> entry : pendingInvites.entrySet()) {
            ListTag ids = new ListTag();
            for (String id : entry.getValue()) ids.add(StringTag.valueOf(id));
            invitesTag.put(entry.getKey().toString(), ids);
        }
        nbt.put("pendingInvites", invitesTag);
        return nbt;
    }

    private static CompoundTag writeFaction(Faction faction) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", faction.id());
        tag.putString("name", faction.name());
        tag.putUUID("leader", faction.leader());
        tag.put("officers", writeUuidList(faction.officers()));
        tag.put("members", writeUuidList(faction.members()));
        return tag;
    }

    private static ListTag writeUuidList(Set<UUID> uuids) {
        ListTag list = new ListTag();
        for (UUID uuid : uuids) list.add(StringTag.valueOf(uuid.toString()));
        return list;
    }

    static FactionsData load(CompoundTag nbt) {
        FactionsData data = new FactionsData();

        ListTag factionList = nbt.getList("factions", Tag.TAG_COMPOUND);
        for (int i = 0; i < factionList.size(); i++) {
            Faction faction = readFaction(factionList.getCompound(i));
            data.factions.put(faction.id(), faction);
            for (UUID member : faction.allMembers()) data.memberIndex.put(member, faction.id());
        }

        CompoundTag invitesTag = nbt.getCompound("pendingInvites");
        for (String key : invitesTag.getAllKeys()) {
            ListTag ids = invitesTag.getList(key, Tag.TAG_STRING);
            Set<String> set = new HashSet<>();
            for (int i = 0; i < ids.size(); i++) set.add(ids.getString(i));
            data.pendingInvites.put(UUID.fromString(key), set);
        }
        return data;
    }

    private static Faction readFaction(CompoundTag tag) {
        Faction faction = new Faction(tag.getString("id"), tag.getString("name"), tag.getUUID("leader"));
        for (String uuid : readUuidStrings(tag.getList("officers", Tag.TAG_STRING))) {
            faction.addOfficer(UUID.fromString(uuid));
        }
        for (String uuid : readUuidStrings(tag.getList("members", Tag.TAG_STRING))) {
            faction.addMember(UUID.fromString(uuid));
        }
        return faction;
    }

    private static Set<String> readUuidStrings(ListTag list) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < list.size(); i++) result.add(list.getString(i));
        return result;
    }

    /** Always reads/writes from the overworld's data storage, same convention as NamesData, so
     *  the roster is shared across all dimensions on the same server. */
    static FactionsData get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        return overworld.getDataStorage().computeIfAbsent(
                FactionsData::load,
                FactionsData::new,
                "shadiomrpoverhaul_factions"
        );
    }
}
