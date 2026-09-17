# Factions: Membership & Roles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let players create factions, invite/accept/decline, kick, promote/demote, leave, and disband, all through a player-facing GUI, with three role tiers (Leader/Officer/Member).

**Architecture:** A new `faction` package holds the data model (`Faction`, pure `FactionPermissions` rules) and world-level persistence (`FactionsData`, a `SavedData` mirroring the existing `names` package's storage pattern). A static `FactionEventHandler` validates every action against `FactionPermissions` and mutates `FactionsData`, invoked by one consolidated C2S packet (`FactionActionC2SPacket`, action-enum + string arg) instead of nine separate packet types. A single `FactionScreen` (client) branches internally between "no faction" and "has faction" views, opened and refreshed by one S2C packet (`OpenFactionScreenS2CPacket`) carrying a full snapshot. A `/faction` command is the sole entry point.

**Tech Stack:** Java 17, Minecraft Forge 1.20.1 (47.4.23), Forge networking (`SimpleChannel`), Brigadier commands, vanilla `Screen`/`Button`/`EditBox` widgets — same stack as every existing feature in this mod, no new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-17-factions-membership-design.md`

## Global Constraints

- Java 17 / Forge 1.20.1 (47.4.23) — match every existing file's language level and imports.
- No test framework exists in this repo (confirmed: `src/test` is empty, no JUnit dependency in `build.gradle`). Per the spec's Testing section, verification is `./gradlew compileJava` per task plus one final in-game manual smoke test — **not** unit tests, except Task 1's `FactionPermissions`, which has zero Minecraft dependencies and gets a real runnable self-check per the ponytail convention already used elsewhere in this codebase (see `NameEventHandler.assignRandomName`'s ponytail comment for the precedent of marking deliberate scope cuts).
- Follow existing package conventions exactly: world-level storage as package-private `SavedData` (`names/NamesData.java` is the template), static business-logic classes with a private constructor (`names/NameEventHandler.java` is the template), S2C/C2S packet pairs as records with `encode`/`decode`/`handle` static methods (`network/names/*` is the template), GUI screens as vanilla-widget-only `Screen` subclasses (`client/names/NamePickerScreen.java` is the template).
- No public `ShadiomFactionAPI` class in this pass (per spec) — `Faction` and `FactionsData` stay package-private; only `FactionEventHandler.openScreen` and `.handleAction` are called from outside the `faction` package.
- New commands are NOT nested under the existing op-only `/shadiomrp` config root — `/faction` is a separate, unrestricted command (per spec, since it isn't a config command).

---

### Task 1: Faction data model & permission rules

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java`

**Interfaces:**
- Produces: `Faction` (mutable class, package `faction`) — constructor `Faction(String id, String name, UUID leader)`; methods `id()`, `name()`, `leader()`, `officers()` (`Set<UUID>`), `members()` (`Set<UUID>`), `allMembers()` (`Set<UUID>`, union of leader+officers+members), `roleOf(UUID)` (`Faction.Role` or `null`), `addMember(UUID)`, `addOfficer(UUID)` (removes from members if present), `demoteToMember(UUID)` (removes from officers, adds to members), `removeMember(UUID)` (removes from both sets). Nested enum `Faction.Role { LEADER, OFFICER, MEMBER }`.
- Produces: `FactionPermissions` (package `faction`, package-private) — static methods `canInvite(Faction.Role actor)`, `canKick(Faction.Role actor, Faction.Role target)`, `canPromote(Faction.Role actor)`, `canDemote(Faction.Role actor)`, `canDisband(Faction.Role actor)`, all `boolean`.

- [ ] **Step 1: Write `Faction.java`**

```java
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
```

- [ ] **Step 2: Write `FactionPermissions.java`**

```java
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

        System.out.println("FactionPermissions self-check passed.");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError("FactionPermissions self-check failed: " + description);
    }
}
```

- [ ] **Step 3: Run the self-check standalone (no Gradle/Forge needed - zero Minecraft imports)**

```bash
mkdir -p /tmp/faction-check && cd /tmp/faction-check
javac -d . /path/to/src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java /path/to/src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java
java -cp . com.tirener.shadiom.shadiomrpoverhaul.faction.FactionPermissions
```

Expected: prints `FactionPermissions self-check passed.` with exit code 0. If a `check(...)` fails, it throws `AssertionError` with the failing description — fix the corresponding method in `FactionPermissions.java` and rerun.

- [ ] **Step 4: Compile the whole project to confirm nothing else broke**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/Faction.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionPermissions.java
git commit -m "Add faction data model and permission rules"
```

---

### Task 2: World-level faction storage

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionsData.java`

**Interfaces:**
- Consumes: `Faction` (Task 1) — `id()`, `name()`, `leader()`, `officers()`, `members()`, `allMembers()`, `addOfficer(UUID)`, `addMember(UUID)`.
- Produces: `FactionsData` (package-private, extends `SavedData`) — instance methods `get(String id)` (`Faction` or `null`), `exists(String id)` (`boolean`), `factionOf(UUID player)` (`Faction` or `null`), `put(Faction)`, `remove(String id)`, `reindex(Faction)` (recomputes the reverse lookup for this faction's current rosters — call after any membership mutation), `invitesOf(UUID player)` (`Set<String>`), `addInvite(UUID, String factionId)`, `removeInvite(UUID, String factionId)`; static `get(ServerLevel level)` (same `computeIfAbsent` pattern as `NamesData.get`).

- [ ] **Step 1: Write `FactionsData.java`**

```java
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
```

- [ ] **Step 2: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. If `putUUID`/`getUUID` on `CompoundTag` don't resolve, check the exact method names via `find ~/.gradle -iname "CompoundTag.java"` under the Forge sources jar (same lookup technique used earlier for `ServerChatEvent`) and adjust.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionsData.java
git commit -m "Add world-level faction storage"
```

---

### Task 3: Business logic, networking, and GUI

These four files are one coupled cluster — `FactionEventHandler` constructs and sends the S2C
packet, the S2C packet's handler opens `FactionScreen`, and `FactionScreen`'s buttons send the
C2S packet back to `FactionEventHandler`. None of them compiles meaningfully alone, so they're
one task per the plan's file-structure guidance ("files that change together should live
together") — write all four, then compile once at the end.

**Files:**
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenFactionScreenS2CPacket.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/FactionScreen.java`
- Create: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/command/FactionCommand.java`
- Modify: `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java`

**Interfaces:**
- Consumes: `Faction`, `FactionPermissions`, `FactionsData` (Tasks 1-2) — full API listed above.
- Produces: `FactionEventHandler.openScreen(ServerPlayer)` and `FactionEventHandler.handleAction(ServerPlayer, FactionActionC2SPacket.Action, String)` — the only two entry points anything outside the `faction` package calls.

- [ ] **Step 1: Write `OpenFactionScreenS2CPacket.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.client.faction.FactionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server to client: opens (or refreshes, after every action) the faction screen with a full
 * snapshot. {@code pendingInviteIds}/{@code pendingInviteNames} and
 * {@code memberNames}/{@code memberRoles} are parallel lists rather than a nested record type,
 * to keep encode/decode to the same flat {@code writeStringList} helper used everywhere else in
 * this mod's networking.
 */
public record OpenFactionScreenS2CPacket(
        boolean hasFaction,
        String factionName,
        String viewerRole,
        List<String> memberNames,
        List<String> memberRoles,
        List<String> invitablePlayerNames,
        List<String> pendingInviteIds,
        List<String> pendingInviteNames
) {

    public static void encode(OpenFactionScreenS2CPacket pkt, FriendlyByteBuf buf) {
        buf.writeBoolean(pkt.hasFaction());
        buf.writeUtf(pkt.factionName());
        buf.writeUtf(pkt.viewerRole());
        writeStringList(buf, pkt.memberNames());
        writeStringList(buf, pkt.memberRoles());
        writeStringList(buf, pkt.invitablePlayerNames());
        writeStringList(buf, pkt.pendingInviteIds());
        writeStringList(buf, pkt.pendingInviteNames());
    }

    public static OpenFactionScreenS2CPacket decode(FriendlyByteBuf buf) {
        return new OpenFactionScreenS2CPacket(
                buf.readBoolean(),
                buf.readUtf(),
                buf.readUtf(),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf),
                readStringList(buf)
        );
    }

    public static void handle(OpenFactionScreenS2CPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() ->
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> openScreen(pkt)));
        ctx.get().setPacketHandled(true);
    }

    private static void openScreen(OpenFactionScreenS2CPacket pkt) {
        Minecraft.getInstance().setScreen(new FactionScreen(pkt));
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
```

- [ ] **Step 2: Write `FactionActionC2SPacket.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.network.faction;

import com.tirener.shadiom.shadiomrpoverhaul.faction.FactionEventHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Client to server: every faction management action goes through this one packet - a
 *  dedicated packet per action (nine of them) would be a lot of boilerplate for what's really
 *  one dispatch on an enum. */
public record FactionActionC2SPacket(Action action, String arg) {

    public enum Action { CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE, DEMOTE, LEAVE, DISBAND }

    public static void encode(FactionActionC2SPacket pkt, FriendlyByteBuf buf) {
        buf.writeEnum(pkt.action());
        buf.writeUtf(pkt.arg());
    }

    public static FactionActionC2SPacket decode(FriendlyByteBuf buf) {
        return new FactionActionC2SPacket(buf.readEnum(Action.class), buf.readUtf());
    }

    public static void handle(FactionActionC2SPacket pkt, Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer player = ctx.get().getSender();
        if (player == null) {
            ctx.get().setPacketHandled(true);
            return;
        }
        ctx.get().enqueueWork(() -> FactionEventHandler.handleAction(player, pkt.action(), pkt.arg()));
        ctx.get().setPacketHandled(true);
    }
}
```

- [ ] **Step 3: Write `FactionEventHandler.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** All faction membership rules live here - validates the actor's role against
 *  {@link FactionPermissions}, mutates {@link FactionsData}, and refreshes the acting player's
 *  screen afterward. {@link FactionsData} itself never validates anything; this is the one place
 *  that does, same division of responsibility as {@code NameEventHandler} vs {@code NamesData}.
 *  Every branch below is a silent no-op on failure (offline target, wrong role, stale state, ...)
 *  per the design spec's error-handling section - the refreshed snapshot sent at the end simply
 *  shows the actor nothing changed. */
public final class FactionEventHandler {

    private FactionEventHandler() {}

    public static void openScreen(ServerPlayer player) {
        send(player);
    }

    public static void handleAction(ServerPlayer player, FactionActionC2SPacket.Action action, String arg) {
        switch (action) {
            case CREATE -> create(player, arg);
            case INVITE -> invite(player, arg);
            case ACCEPT -> acceptInvite(player, arg);
            case DECLINE -> declineInvite(player, arg);
            case KICK -> kick(player, arg);
            case PROMOTE -> promote(player, arg);
            case DEMOTE -> demote(player, arg);
            case LEAVE -> leave(player);
            case DISBAND -> disband(player);
        }
        send(player);
    }

    private static void create(ServerPlayer player, String name) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        if (name == null || name.isBlank()) return;

        String id = slug(name);
        if (data.exists(id)) return;

        data.put(new Faction(id, name, player.getUUID()));
    }

    private static void invite(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canInvite(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (data.factionOf(target.getUUID()) != null) return; // also rejects self-invite

        data.addInvite(target.getUUID(), faction.id());
        send(target);
    }

    private static void acceptInvite(ServerPlayer player, String factionId) {
        FactionsData data = data(player);
        if (data.factionOf(player.getUUID()) != null) return;
        if (!data.invitesOf(player.getUUID()).contains(factionId)) return;

        Faction faction = data.get(factionId);
        if (faction == null) {
            data.removeInvite(player.getUUID(), factionId);
            return;
        }

        faction.addMember(player.getUUID());
        data.reindex(faction);
        clearInvites(data, player.getUUID());
    }

    private static void declineInvite(ServerPlayer player, String factionId) {
        data(player).removeInvite(player.getUUID(), factionId);
    }

    private static void kick(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        Faction.Role targetRole = faction.roleOf(target.getUUID());
        if (targetRole == null) return;
        if (!FactionPermissions.canKick(faction.roleOf(actor.getUUID()), targetRole)) return;

        faction.removeMember(target.getUUID());
        data.reindex(faction);
        send(target);
    }

    private static void promote(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canPromote(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (faction.roleOf(target.getUUID()) != Faction.Role.MEMBER) return;

        faction.addOfficer(target.getUUID());
        data.reindex(faction);
    }

    private static void demote(ServerPlayer actor, String targetName) {
        FactionsData data = data(actor);
        Faction faction = data.factionOf(actor.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDemote(faction.roleOf(actor.getUUID()))) return;

        ServerPlayer target = actor.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) return;
        if (faction.roleOf(target.getUUID()) != Faction.Role.OFFICER) return;

        faction.demoteToMember(target.getUUID());
        data.reindex(faction);
    }

    private static void leave(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (faction.roleOf(player.getUUID()) == Faction.Role.LEADER) return; // must disband instead

        faction.removeMember(player.getUUID());
        data.reindex(faction);
    }

    private static void disband(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());
        if (faction == null) return;
        if (!FactionPermissions.canDisband(faction.roleOf(player.getUUID()))) return;

        data.remove(faction.id());
    }

    private static void clearInvites(FactionsData data, UUID player) {
        for (String id : List.copyOf(data.invitesOf(player))) data.removeInvite(player, id);
    }

    private static String slug(String name) {
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private static FactionsData data(ServerPlayer player) {
        return FactionsData.get(player.serverLevel());
    }

    // -- Snapshot --

    private static void send(ServerPlayer player) {
        ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), buildSnapshot(player));
    }

    private static OpenFactionScreenS2CPacket buildSnapshot(ServerPlayer player) {
        FactionsData data = data(player);
        Faction faction = data.factionOf(player.getUUID());

        if (faction == null) {
            List<String> inviteIds = new ArrayList<>();
            List<String> inviteNames = new ArrayList<>();
            for (String id : data.invitesOf(player.getUUID())) {
                Faction invited = data.get(id);
                if (invited == null) continue;
                inviteIds.add(id);
                inviteNames.add(invited.name());
            }
            return new OpenFactionScreenS2CPacket(false, "", "", List.of(), List.of(), List.of(),
                    inviteIds, inviteNames);
        }

        List<String> memberNames = new ArrayList<>();
        List<String> memberRoles = new ArrayList<>();
        MinecraftServer server = player.getServer();
        for (UUID uuid : faction.allMembers()) {
            ServerPlayer member = server.getPlayerList().getPlayer(uuid);
            if (member == null) continue; // offline members aren't listed - see design spec
            memberNames.add(member.getGameProfile().getName());
            memberRoles.add(faction.roleOf(uuid).name());
        }

        List<String> invitable = new ArrayList<>();
        for (ServerPlayer online : server.getPlayerList().getPlayers()) {
            if (data.factionOf(online.getUUID()) != null) continue;
            invitable.add(online.getGameProfile().getName());
        }

        String viewerRole = faction.roleOf(player.getUUID()).name();
        return new OpenFactionScreenS2CPacket(true, faction.name(), viewerRole,
                memberNames, memberRoles, invitable, List.of(), List.of());
    }
}
```

- [ ] **Step 4: Write `FactionScreen.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.client.faction;

import com.tirener.shadiom.shadiomrpoverhaul.network.ModNetwork;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket.Action;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * Faction management GUI - branches internally on whether the player has a faction, same
 * single-screen-two-views approach as {@code NamePickerScreen}'s error-retry state. Every button
 * press sends a {@link FactionActionC2SPacket} and waits for the server's refreshed
 * {@link OpenFactionScreenS2CPacket} to replace this screen - no client-side state mutation.
 * <p>
 * ponytail: each list below is capped at {@link #MAX_ROWS} with no scrolling - add scrolling
 * (see {@code NamePickerScreen} for the pattern already used elsewhere in this mod) if a
 * faction or the online-player list actually exceeds it.
 */
@OnlyIn(Dist.CLIENT)
public class FactionScreen extends Screen {

    private static final int PANEL_W = 320;
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 22;
    private static final int MAX_ROWS = 5;
    private static final int BUTTON_W = 60;

    private final OpenFactionScreenS2CPacket state;
    private final List<Label> labels = new ArrayList<>();

    private EditBox nameField;
    private int left, top, y;

    public FactionScreen(OpenFactionScreenS2CPacket state) {
        super(Component.literal("Factions"));
        this.state = state;
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = 20;
        y = top + 24;
        labels.clear();

        if (state.hasFaction()) initFactionView(); else initNoFactionView();
    }

    private void initNoFactionView() {
        nameField = new EditBox(font, left, y, PANEL_W - 70, ROW_H, Component.literal("Faction name"));
        addRenderableWidget(nameField);
        addRenderableWidget(Button.builder(Component.literal("Create"), b -> onCreate())
                .pos(left + PANEL_W - 65, y).size(65, ROW_H).build());
        y += ROW_GAP + 12;

        int shown = 0;
        for (int i = 0; i < state.pendingInviteIds().size() && shown < MAX_ROWS; i++, shown++) {
            String id = state.pendingInviteIds().get(i);
            String name = state.pendingInviteNames().get(i);
            addRow(Component.literal(name), List.of(
                    new RowButton("Accept", b -> send(Action.ACCEPT, id)),
                    new RowButton("Decline", b -> send(Action.DECLINE, id))));
        }
    }

    private void initFactionView() {
        boolean canManage = state.viewerRole().equals("LEADER") || state.viewerRole().equals("OFFICER");
        boolean isLeader = state.viewerRole().equals("LEADER");
        String selfName = minecraft.player.getGameProfile().getName();

        y += 12;
        int shown = 0;
        for (int i = 0; i < state.memberNames().size() && shown < MAX_ROWS; i++, shown++) {
            String name = state.memberNames().get(i);
            String role = state.memberRoles().get(i);
            boolean self = name.equals(selfName);

            List<RowButton> buttons = new ArrayList<>();
            if (canManage && !self) buttons.add(new RowButton("Kick", b -> send(Action.KICK, name)));
            if (isLeader && !self && role.equals("MEMBER")) {
                buttons.add(new RowButton("Promote", b -> send(Action.PROMOTE, name)));
            }
            if (isLeader && !self && role.equals("OFFICER")) {
                buttons.add(new RowButton("Demote", b -> send(Action.DEMOTE, name)));
            }
            addRow(Component.literal(name + " - " + role), buttons);
        }

        if (canManage) {
            y += 12;
            shown = 0;
            for (int i = 0; i < state.invitablePlayerNames().size() && shown < MAX_ROWS; i++, shown++) {
                String name = state.invitablePlayerNames().get(i);
                addRow(Component.literal(name), List.of(new RowButton("Invite", b -> send(Action.INVITE, name))));
            }
        }

        y += 12;
        if (isLeader) {
            addRenderableWidget(Button.builder(Component.literal("Disband"), b -> send(Action.DISBAND, ""))
                    .pos(left, y).size(PANEL_W, ROW_H).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal("Leave"), b -> send(Action.LEAVE, ""))
                    .pos(left, y).size(PANEL_W, ROW_H).build());
        }
    }

    /** Lays out a label on the left with 0-3 action buttons packed against the right edge. */
    private void addRow(Component label, List<RowButton> buttons) {
        int x = left + PANEL_W;
        for (RowButton rb : buttons) {
            x -= BUTTON_W + 5;
            addRenderableWidget(Button.builder(Component.literal(rb.label()), rb.onPress())
                    .pos(x, y).size(BUTTON_W, ROW_H).build());
        }
        labels.add(new Label(label, left, y));
        y += ROW_GAP;
    }

    private void onCreate() {
        if (nameField.getValue().isBlank()) return;
        send(Action.CREATE, nameField.getValue());
    }

    private void send(Action action, String arg) {
        ModNetwork.CHANNEL.sendToServer(new FactionActionC2SPacket(action, arg));
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g);
        String title = state.hasFaction() ? state.factionName() + " (" + state.viewerRole() + ")" : "Factions";
        g.drawCenteredString(font, title, left + PANEL_W / 2, top, 0xFFFFFF);

        super.render(g, mx, my, partial);

        for (Label label : labels) {
            g.drawString(font, label.text(), label.x(), label.y() + 6, ChatFormatting.GRAY.getColor());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record Label(Component text, int x, int y) {}
    private record RowButton(String label, Button.OnPress onPress) {}
}
```

- [ ] **Step 5: Write `FactionCommand.java`**

```java
package com.tirener.shadiom.shadiomrpoverhaul.command;

import com.mojang.brigadier.CommandDispatcher;
import com.tirener.shadiom.shadiomrpoverhaul.Shadiomrpoverhaul;
import com.tirener.shadiom.shadiomrpoverhaul.faction.FactionEventHandler;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Opens the faction management GUI - every actual action happens through that screen, not
 *  subcommands, per the design spec. Any player may run it (not nested under the op-only
 *  {@code /shadiomrp} config root - this isn't a config command). */
@Mod.EventBusSubscriber(modid = Shadiomrpoverhaul.MODID)
public final class FactionCommand {

    private FactionCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("faction")
                .executes(ctx -> {
                    if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) return 0;
                    FactionEventHandler.openScreen(player);
                    return 1;
                }));
    }
}
```

- [ ] **Step 6: Register both packets in `ModNetwork.java`**

Modify `src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java`. Add imports next to the existing `network.names`/`network.title` imports:

```java
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.FactionActionC2SPacket;
import com.tirener.shadiom.shadiomrpoverhaul.network.faction.OpenFactionScreenS2CPacket;
```

Add two new id constants after `ID_NAME_SYNC`:

```java
    private static final int ID_OPEN_FACTION_SCREEN = 4;
    private static final int ID_FACTION_ACTION = 5;
```

Add two new registrations inside `register()`, after the existing `ID_NAME_SYNC` block:

```java
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
```

- [ ] **Step 7: Compile**

Run: `./gradlew compileJava --console=plain`
Expected: `BUILD SUCCESSFUL`. Likely mismatches to watch for, same technique as earlier in this
session (extract the relevant class from
`~/.gradle/caches/forge_gradle/maven_downloader/net/minecraftforge/forge/1.20.1-47.4.23/forge-1.20.1-47.4.23-sources.jar`
to check the exact signature):
- `CompoundTag.putUUID`/`getUUID` (Task 2, if not already confirmed)
- `FriendlyByteBuf.writeEnum`/`readEnum`
- `EditBox`'s 6-arg constructor `(Font, int, int, int, int, Component)`
- `GuiGraphics.drawString(Font, Component, int, int, int)`
- `Button.Builder.pos(int, int)` / `.size(int, int)` (already proven working in `NamePickerScreen`)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/OpenFactionScreenS2CPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/faction/FactionActionC2SPacket.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/faction/FactionEventHandler.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/client/faction/FactionScreen.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/command/FactionCommand.java src/main/java/com/tirener/shadiom/shadiomrpoverhaul/network/ModNetwork.java
git commit -m "Add faction networking, GUI, and /faction command"
```

---

### Task 4: In-game smoke test

No test harness exists in this repo, so this is the plan's actual end-to-end verification, per
the design spec's Testing section. Needs two Minecraft accounts/clients connected to the same
dev server (`./gradlew runServer` in one terminal, `./gradlew runClient` for each player, or two
`runClient` instances if using an offline/cracked dev setup).

- [ ] **Step 1: Start a dev server and two clients**

Run: `./gradlew runServer` (leave running), then `./gradlew runClient` twice (or once per
configured client run if the project only has one `runClient` task - check
`./gradlew tasks --group="forgegradle runs"` for the exact task names).

- [ ] **Step 2: Walk the full rules table with Player A and Player B**

1. Player A runs `/faction` — expect the "no faction" view: name field + Create button, no
   pending invites.
2. Player A types a name and clicks Create — expect the screen to refresh into the "has
   faction" view, Player A listed as LEADER, Disband button visible (not Leave).
3. Player A opens `/faction` again, invites Player B from the invite panel (Player B must be
   online) — expect no visible error; Player B's own next `/faction` open should show the
   pending invite with Accept/Decline.
4. Player B runs `/faction`, sees the pending invite, clicks Accept — expect Player B now shows
   the "has faction" view as MEMBER, and Player A's next `/faction` open shows Player B in the
   member list.
5. Player A promotes Player B to OFFICER — expect Player B's role updates to OFFICER on next
   screen open, and Player B's Kick/Invite buttons become visible for members (not for the
   leader).
6. Player A demotes Player B back to MEMBER — expect Player B loses officer-only buttons.
7. Player A kicks Player B — expect Player B no longer in the member list, and Player B's own
   `/faction` shows the "no faction" view again (no lingering invite).
8. Re-invite and re-accept Player B, then have Player B leave voluntarily via the Leave button —
   expect the same "no faction" result for Player B without Player A doing anything.
9. Confirm Player A (leader) has no Leave button, only Disband.
10. Player A clicks Disband — expect Player A's screen returns to "no faction", and (if Player B
    was re-added before this step) Player B's faction membership is also gone next time they
    check.
11. Restart the server and reopen `/faction` as Player A after recreating a faction — expect the
    faction to still exist (confirms `FactionsData` NBT persistence round-trips correctly).

- [ ] **Step 3: Fix anything that doesn't match, recompiling after each fix**

If a step doesn't behave as expected, the bug is almost always in `FactionEventHandler` (wrong
permission check or wrong set mutation) or `FactionScreen` (wrong button visibility condition) -
both are plain files to re-read and adjust, no new files needed for a v1 bug fix.

- [ ] **Step 4: Stop the dev server/clients, commit if any fixes were made**

```bash
git add -A
git commit -m "Fix issues found in factions in-game smoke test"
```

(Skip this commit if step 2 required no changes.)
