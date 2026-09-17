# Factions: Land Claims & Faction Center (v1)

Sub-project 2 of the factions effort (see
`2026-09-17-factions-membership-design.md` for sub-project 1, which this depends on). Scope:
a placeable Faction Center block that founds a faction and anchors its territory, chunk-based
claims that grow outward from Faction Centers, build/break protection inside claims, and two new
faction-screen features (claim/unclaim the current chunk, toggle a translucent territory
border). Diplomacy (sub-project 3) is still out of scope.

## Faction Center block

A plain block (no custom `Block` subclass needed - a stock `Block` instance registered via
`DeferredRegister`, following the standard Forge MDK registration pattern this mod hasn't used
yet). Its texture already exists at `assets/textures/blocks/faction_center.png` but at the wrong
path for Minecraft to find - it needs to move to
`assets/shadiomrpoverhaul/textures/block/faction_center.png` (namespaced under the modid,
singular `block`). A minimal blockstate, block model (`block/cube_all` parent), item model
(parent to the block model), an `en_us.json` lang entry, and a self-drop loot table are the
other new resource files a functional, obtainable block needs - none of that exists yet either.

**Placement rules** (enforced in `ClaimProtectionHandler`'s `BlockEvent.EntityPlaceEvent`
handler):
- Placer has no faction: allowed only if the target chunk is unclaimed. Placement succeeds, and
  it becomes a *pending capital* for that player (see "Founding a faction" below) rather than
  immediately joining a faction, since none exists yet.
- Placer has a faction: allowed only if they're the leader (`FactionPermissions.canPlaceFactionCenter`,
  a leader-only rule - its own named method even though identical in body to `canDisband`, since
  they're different concerns that happen to share a tier today), and only if the target chunk is
  unclaimed or already owned by their own faction. On success, that chunk joins their faction's
  claims (or has its existing claim entry's `capital` flag set to true, if already owned) as a
  new capital. A faction can have more than one Faction Center - each is its own territory root,
  so a faction's land can end up as several disconnected blobs, each grown from its own capital.
- Any other case (chunk already claimed by a different faction; a faction member who isn't the
  leader) cancels the placement - the block doesn't appear, item isn't consumed.

**Breaking rules** (in the same handler's `BlockEvent.BreakEvent`): only the owning faction's
leader may break a Faction Center (`FactionPermissions.canBreakFactionCenter`, another
leader-only rule of its own). Breaking one releases that chunk back to unclaimed - there is no
"unclaim via GUI" path for a capital chunk (see Claims below); breaking the block is the only
way to give one up.

## Founding a faction

The `/faction` GUI's no-faction view loses its name-field/Create button entirely - it now shows
only pending invites (or nothing, if none). **The only way to found a faction is placing a
Faction Center as a faction-less player.**

When that happens, the server records a transient *pending capital* for that player - a small
in-memory `Map<UUID, PendingCapital>` (dimension + chunk X/Z) held in `FactionEventHandler`,
mirroring the existing `PENDING_PICKS` map in `NameEventHandler` for the same kind of
"mid-flow, not yet persisted" state - and immediately forces open the faction screen. That
screen, when a pending capital exists, shows *only* a name field and a Create button - no
invites, no way to back out, `shouldCloseOnEsc()` returns false and `onClose()` is empty, the
same "cannot be dismissed, the only way out is completing it" pattern `NamePickerScreen` already
uses for name-picking in this codebase.

Submitting a name re-validates everything the existing `create` action already checks (not
already in a faction, name not blank, id not taken) **plus** requires a pending capital to still
be recorded for that player - enforced server-side, not just by what the GUI shows, matching
this mod's established "never trust the client" stance. On success: the faction is created with
them as leader, the pending chunk is claimed as its first capital, and the pending-capital entry
is cleared.

This is in-memory, not persisted, so a player who disconnects mid-creation (placed the block,
never submitted a name) keeps their pending capital across reconnects - logging back in and
running `/faction` shows the same forced create screen again. The physically-placed block sits
there, unclaimed and faction-less, until they either complete creation or an admin removes it by
hand. Accepted as a rare edge case for v1, not worth a cleanup mechanism.

## Claims

New `ClaimsData` (world-level `SavedData`, same shape and NBT-round-trip pattern as
`FactionsData`): `Map<chunkKey, ClaimEntry(factionId, capital)>` plus a reverse
`Map<factionId, Set<chunkKey>>` so a faction disbanding can release every chunk it owns in one
pass (`FactionEventHandler.disband()` now also calls `ClaimsData.releaseAll(factionId)` - an
orphaned claim pointing at a deleted faction would otherwise sit there forever, exactly the same
concern `FactionsData.reindex` already solves for the player-index side). `chunkKey` is a string
like `"minecraft:overworld,3,-5"` (dimension location + chunk X + chunk Z, comma-joined - safe to
split naively on `,` since a `ResourceLocation`'s string form never contains one).

Faction Center placement is exempt from the adjacency rule below - it's how a faction bootstraps
a new territory root in the first place, so it can land anywhere unclaimed (or on the faction's
own land, to add the capital flag) regardless of what else the faction owns.

**Claiming a chunk** (LEADER/OFFICER only - `FactionPermissions.canManageClaims`, its own method
even though identical to `canInvite` today, for the same reasons as the two Faction Center
permission methods above): acts on whatever chunk the player is currently standing in. Requires
the chunk to be unclaimed AND orthogonally adjacent (one of the 4 neighboring chunks) to a chunk
the faction already owns - capital or not, so territory grows outward from wherever Faction
Centers are planted, without a hard cap on how far. No claim limit, no cross-faction overlap.

**Unclaiming**: same permission tier, same "current chunk" target. Refuses if the chunk isn't
owned by the acting player's faction, or if it's a capital chunk (must break the Faction Center
instead, per above).

## Protection

`ClaimProtectionHandler` (new `@Mod.EventBusSubscriber` in the `faction` package, alongside
`FactionEventHandler`) hooks two events:
- `BlockEvent.BreakEvent`: if the broken block's chunk is claimed, only a member of the owning
  faction may break it - except a Faction Center specifically, which additionally requires the
  leader tier (see Breaking rules above).
- `BlockEvent.EntityPlaceEvent`: if the target chunk is claimed, only a member of the owning
  faction may place there - except a Faction Center specifically, which follows the Placement
  rules above instead (its own faction-founding/capital-adding logic, not the generic
  member-only rule).

No bypass for creative mode or ops in v1 - out of scope, same as the rest of this feature.
Explosions, fire spread, and interaction protection (chests, doors, buttons) are explicitly not
covered - build/break only, matching the original scope line from the first factions brainstorm.

## GUI

Two additions to the existing has-faction view in `FactionScreen`:
- A contextual **Claim this chunk** / **Unclaim this chunk** button (based on who currently owns
  wherever the player is standing), shown only when `canManageClaims` is true for the viewer.
  Sends the existing `FactionActionC2SPacket` with two new `Action` values, `CLAIM`/`UNCLAIM` -
  no argument needed, since the server always acts on the player's current chunk at the moment
  the packet is handled.
- **Show Faction Area** - a persistent toggle (per your answer: stays on until clicked again,
  not timed) available to any member, not just claim-managers, since viewing your own territory
  is lower-stakes than changing it. Toggling on hands the client-side renderer the faction's
  claimed-chunk list *as of the last snapshot* - it does not live-update while showing; if your
  territory changes while the border's up, toggling off and back on refreshes it. This is a
  deliberate v1 simplification, not an oversight.

The snapshot packet (`OpenFactionScreenS2CPacket`) grows four fields: `mustCreateFaction`
(drives the forced create-only view described above), `currentChunkOwner` (the faction name
owning wherever the viewer is standing, empty if unclaimed - meaningful only when `hasFaction`),
`canManageClaims` (the viewer's own permission), and `ownClaimedChunkKeys` (their faction's
claimed chunks in the *viewer's current dimension only* - filtered server-side, since border
rendering only makes sense for the dimension you're standing in).

## Rendering

New client-only `ClaimBorderRenderer`, holding a static `enabled` flag and the last-toggled-on
chunk list, subscribed to Forge's `RenderLevelStageEvent` (`Stage.AFTER_TRANSLUCENT_BLOCKS`).
For each claimed chunk, draws a translucent vertical quad on each of its 4 edges *only where the
neighboring chunk isn't also claimed* - i.e. the outer boundary only, not a grid of redundant
internal walls, so it actually reads as a territory outline rather than clutter. Full world
height (matching the classic claim-plugin look), manual `Tesselator`/`BufferBuilder` with
`DefaultVertexFormat.POSITION_COLOR` and `GameRenderer::getPositionColorShader` - verified
against vanilla's own `LevelRenderer.renderShape` (the block-selection outline renderer) for the
exact `vertex(matrix, x, y, z).color(r, g, b, a).endVertex()` calling convention, so the API
usage itself is solid; whether the geometry actually looks right is something only an in-game
check can confirm; the compiler can't verify visual output.

## Error handling

Every new action (claim, unclaim, Faction Center placement/breaking) is a silent no-op on
failure - same convention `FactionEventHandler` already follows for membership actions - and the
snapshot sent afterward simply reflects whatever did or didn't change. Placement/break rules are
enforced in `ClaimProtectionHandler` by cancelling the Forge event outright, which prevents the
block change from happening at all (not a rollback after the fact).

## Testing

Same as sub-project 1: no test harness exists in this repo, so verification is
`./gradlew compileJava` per task plus an in-game smoke test at the end, covering: a faction-less
player placing a Faction Center (forced create screen, chunk becomes capital on submit), a
second player placing one without being a leader (blocked), claiming a chunk adjacent to the
capital (succeeds) versus a non-adjacent one (blocked), a non-member trying to break/place inside
the claim (blocked) versus a member (allowed), breaking the Faction Center as a non-leader
(blocked) versus the leader (allowed, releases the chunk), toggling Show Faction Area and
visually confirming the border only appears on the outer edge of the claimed area, and disbanding
a faction with claims and confirming they're released (a third party can then claim that land).

## Out of scope (unchanged from sub-project 1's list, plus)

- Diplomacy (sub-project 3, still depends on sub-project 1 only).
- Claim limits, adjacency exceptions, cross-dimension capital linking.
- Explosion/fire/interaction protection inside claims.
- Live-updating the territory border while it's showing.
- Creative-mode or op bypass of claim protection.
- A claims list/map view in the GUI (only current-chunk status).
