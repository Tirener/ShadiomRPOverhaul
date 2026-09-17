package com.tirener.shadiom.shadiomrpoverhaul.faction;

/** Pure role-permission rules - no Minecraft types, so unlike everything else in this feature
 *  it's directly runnable and checkable via {@link #main} without a game instance. */
final class FactionPermissions {

    private FactionPermissions() {}

    static boolean canInvite(Faction.Role actor) {
        return actor == Faction.Role.LEADER || actor == Faction.Role.OFFICER;
    }

    static boolean canKick(Faction.Role actor, Faction.Role target) {
        if (actor == Faction.Role.LEADER) return target != Faction.Role.LEADER;
        if (actor == Faction.Role.OFFICER) return target == Faction.Role.MEMBER;
        return false;
    }

    static boolean canPromote(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }

    static boolean canDemote(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }

    static boolean canDisband(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }

    static boolean canManageClaims(Faction.Role actor) {
        return actor == Faction.Role.LEADER || actor == Faction.Role.OFFICER;
    }

    static boolean canPlaceFactionCenter(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }

    static boolean canBreakFactionCenter(Faction.Role actor) {
        return actor == Faction.Role.LEADER;
    }

    public static void main(String[] args) {
        check(canInvite(Faction.Role.LEADER), "leader can invite");
        check(canInvite(Faction.Role.OFFICER), "officer can invite");
        check(!canInvite(Faction.Role.MEMBER), "member cannot invite");

        check(canKick(Faction.Role.LEADER, Faction.Role.OFFICER), "leader can kick officer");
        check(canKick(Faction.Role.LEADER, Faction.Role.MEMBER), "leader can kick member");
        check(!canKick(Faction.Role.LEADER, Faction.Role.LEADER), "leader cannot kick leader");
        check(canKick(Faction.Role.OFFICER, Faction.Role.MEMBER), "officer can kick member");
        check(!canKick(Faction.Role.OFFICER, Faction.Role.OFFICER), "officer cannot kick officer");
        check(!canKick(Faction.Role.OFFICER, Faction.Role.LEADER), "officer cannot kick leader");
        check(!canKick(Faction.Role.MEMBER, Faction.Role.MEMBER), "member cannot kick");

        check(canPromote(Faction.Role.LEADER), "leader can promote");
        check(!canPromote(Faction.Role.OFFICER), "officer cannot promote");
        check(!canPromote(Faction.Role.MEMBER), "member cannot promote");

        check(canDemote(Faction.Role.LEADER), "leader can demote");
        check(!canDemote(Faction.Role.OFFICER), "officer cannot demote");

        check(canDisband(Faction.Role.LEADER), "leader can disband");
        check(!canDisband(Faction.Role.OFFICER), "officer cannot disband");
        check(!canDisband(Faction.Role.MEMBER), "member cannot disband");

        check(canManageClaims(Faction.Role.LEADER), "leader can manage claims");
        check(canManageClaims(Faction.Role.OFFICER), "officer can manage claims");
        check(!canManageClaims(Faction.Role.MEMBER), "member cannot manage claims");

        check(canPlaceFactionCenter(Faction.Role.LEADER), "leader can place a faction center");
        check(!canPlaceFactionCenter(Faction.Role.OFFICER), "officer cannot place a faction center");
        check(!canPlaceFactionCenter(Faction.Role.MEMBER), "member cannot place a faction center");

        check(canBreakFactionCenter(Faction.Role.LEADER), "leader can break a faction center");
        check(!canBreakFactionCenter(Faction.Role.OFFICER), "officer cannot break a faction center");
        check(!canBreakFactionCenter(Faction.Role.MEMBER), "member cannot break a faction center");

        System.out.println("FactionPermissions self-check passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("FactionPermissions self-check failed: " + description);
    }
}
