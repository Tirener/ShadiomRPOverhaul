package com.tirener.shadiom.shadiomrpoverhaul.faction;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
    private final Map<String, Territory> territories = new HashMap<>();
    private String capitalTerritoryId;

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

    Map<String, Territory> territories() { return territories; }

    Territory territory(String id) { return territories.get(id); }

    void addTerritory(Territory territory) { territories.put(territory.id(), territory); }

    void removeTerritory(String id) { territories.remove(id); }

    void renameTerritory(String id, String name) {
        Territory existing = territories.get(id);
        if (existing != null) territories.put(id, new Territory(id, name));
    }

    String capitalTerritoryId() { return capitalTerritoryId; }

    void setCapitalTerritoryId(String id) { capitalTerritoryId = id; }

    enum Role { LEADER, OFFICER, MEMBER }

    /** One of a faction's separately-founded, separately-capped landmasses, each anchored by its
     *  own Faction Center. {@code name} is null until the leader sets one - see
     *  the territories design spec for the naming UI. */
    record Territory(String id, String name) {}

    public static void main(String[] args) {
        Faction faction = new Faction("test", "Test Faction", UUID.randomUUID());
        check(faction.territories().isEmpty(), "new faction has no territories");
        check(faction.capitalTerritoryId() == null, "new faction has no capital yet");

        faction.addTerritory(new Territory("t1", null));
        check(faction.territory("t1") != null, "territory t1 was added");
        check(faction.territory("t1").name() == null, "territory t1 starts unnamed");

        faction.renameTerritory("t1", "Home");
        check("Home".equals(faction.territory("t1").name()), "territory t1 renamed to Home");

        faction.setCapitalTerritoryId("t1");
        check("t1".equals(faction.capitalTerritoryId()), "t1 set as capital");

        faction.addTerritory(new Territory("t2", null));
        faction.removeTerritory("t2");
        check(faction.territory("t2") == null, "territory t2 removed");

        System.out.println("Faction self-check passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("Faction self-check failed: " + description);
    }
}
