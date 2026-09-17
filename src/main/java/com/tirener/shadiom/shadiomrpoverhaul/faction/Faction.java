package com.tirener.shadiom.shadiomrpoverhaul.faction;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** A faction: a stable id (lowercased slug of the name), the as-typed display name, its leader,
 *  and its officer/member rosters. The leader never changes hands in v1 (see design spec), so
 *  it's a plain final field; officers/members are mutated in place as roles change. */
final class Faction {

    private final String id;
    private final String name;
    private final UUID leader;
    private final Set<UUID> officers = new HashSet<>();
    private final Set<UUID> members = new HashSet<>();

    Faction(String id, String name, UUID leader) {
        this.id = id;
        this.name = name;
        this.leader = leader;
    }

    String id() { return id; }
    String name() { return name; }
    UUID leader() { return leader; }
    Set<UUID> officers() { return officers; }
    Set<UUID> members() { return members; }

    /** Every UUID currently in this faction, regardless of role. */
    Set<UUID> allMembers() {
        Set<UUID> all = new HashSet<>(members);
        all.addAll(officers);
        all.add(leader);
        return all;
    }

    Role roleOf(UUID player) {
        if (player.equals(leader)) return Role.LEADER;
        if (officers.contains(player)) return Role.OFFICER;
        if (members.contains(player)) return Role.MEMBER;
        return null;
    }

    void addMember(UUID player) { members.add(player); }
    void addOfficer(UUID player) { officers.add(player); members.remove(player); }
    void demoteToMember(UUID player) { officers.remove(player); members.add(player); }
    void removeMember(UUID player) { members.remove(player); officers.remove(player); }

    enum Role { LEADER, OFFICER, MEMBER }
}
