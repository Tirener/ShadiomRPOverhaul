# Factions: Diplomacy (v1)

Sub-project 3 of the factions effort (see `2026-09-17-factions-membership-design.md` for
sub-project 1, which this depends on; independent of sub-project 2's land claims). Scope:
relations between factions (neutral/ally/war) and the GUI to manage them. No PvP hooks, no
claim-protection interaction - purely relational and informational, per the brainstorm: PvP
stays exactly as the server already has it configured, unaffected by faction relations.

## Relations

Three states per *pair* of factions: `NEUTRAL` (default - absence from storage, same convention
`ClaimsData` uses for unclaimed chunks), `ALLY`, `WAR`. Undirected - a pair is simply in one
state; there is no separate "A's view of B" vs "B's view of A".

**Transitions**, all leader-only (`FactionPermissions.canManageDiplomacy`, its own method even
though identical in body to `canDisband` - a faction-defining decision, not day-to-day
management like invite/claim):
- `NEUTRAL → WAR`: unilateral, either leader declares it.
- `WAR → NEUTRAL`: unilateral, either leader ends it ("make peace").
- `→ ALLY`: mutual - one leader proposes, the other's leader accepts or declines, same
  request/accept shape as the existing player-invite flow. Works from any starting state,
  including WAR - accepting an alliance proposal while at war ends the war and allies in the
  same action.
- `ALLY → NEUTRAL`: unilateral, either leader breaks the alliance.
- Declaring war unconditionally overrides an existing alliance - no guard against declaring war
  on a current ally. Simplest rule, no extra special-casing for what is thematically a valid
  (if dramatic) move.

## Data model

New `DiplomacyData` (world-level `SavedData`, same shape and NBT-round-trip pattern as
`ClaimsData`):
- `Map<pairKey, Relation>` - only `ALLY`/`WAR` entries are ever stored; a missing entry means
  `NEUTRAL`. `pairKey` is a canonical ordering of the two faction ids
  (`a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a`) so `(A, B)` and `(B, A)` always resolve to
  the same key.
- `Map<factionId, Set<factionId>>` of pending incoming alliance proposals, keyed by the
  *receiving* faction - mirrors `FactionsData`'s `pendingInvites` shape exactly.

No reverse index by faction for cleanup - `disband()` (in `FactionEventHandler`, already handles
this same orphan-prevention concern for claims via `ClaimsData.releaseAll`) does a plain linear
scan over both maps removing any entry that references the disbanding faction. The expected
scale (a handful of relations per faction) doesn't justify a reverse index yet.

## Business logic

Lives in `FactionEventHandler` alongside membership and claim actions (no new event-handler
class needed - unlike claims, diplomacy has no Forge event to hook, it's pure request/response
GUI logic like membership). Six new `FactionActionC2SPacket.Action` values: `DECLARE_WAR`,
`MAKE_PEACE`, `PROPOSE_ALLIANCE`, `ACCEPT_ALLIANCE`, `DECLINE_ALLIANCE`, `BREAK_ALLIANCE`. Every
action's `arg` is the target faction's *display name*, not a separate id - resolved server-side
via `slug(arg)` the same deterministic way faction ids are already derived from names elsewhere,
which avoids adding a redundant id list to the snapshot packet (the same trick that kept the
claims snapshot from needing yet another parallel list).

Same silent-no-op-on-failure convention as every other action in this mod: not your faction's
leader, target faction doesn't exist, or the transition doesn't apply (e.g. accepting a proposal
that was already withdrawn) all just result in nothing changing, reflected by the refreshed
snapshot.

## GUI

A new section in `FactionScreen`: a capped list of every *other* known faction (the viewer's own
faction is excluded, same as the invite panel already excludes existing members) - same
`MAX_ROWS`, no-scroll convention already used for members/invites/claims, explicitly accepted
here too; this makes the screen taller but scrolling is still out of scope - showing each one's
current relation and contextual buttons:
- `NEUTRAL`: **Declare War**, **Propose Alliance**
- `WAR`: **Make Peace**, **Propose Alliance**
- `ALLY`: **Break Alliance**

Plus a small incoming-proposals list (other factions' pending alliance offers to yours) with
**Accept**/**Decline** per row. All of this only renders when the viewer has a faction and is
gated on a new `canManageDiplomacy` snapshot field (LEADER only) for the action buttons - any
member can still *see* the relations list, only the leader gets buttons.

## Networking

Extends the existing packets rather than adding new ones, same pattern as claims:
- `FactionActionC2SPacket.Action` gains the six values above.
- `OpenFactionScreenS2CPacket` gains three fields: `otherFactionNames` + `otherFactionRelations`
  (parallel lists, relation as `"NEUTRAL"`/`"ALLY"`/`"WAR"`), `incomingProposalNames`, and
  `canManageDiplomacy` (boolean). All three list/boolean fields are empty/false when the viewer
  has no faction, same gating as the existing claim-related fields.

## Diplomacy report (HTML)

A new `DiplomacyReportWriter` (package `faction`) writes a static HTML file to
`<world save folder>/ShadiomRP/diplomacy.html` via `server.getWorldPath(LevelResource.ROOT)` -
openable in any browser, outside the game. One table, one row per faction (alphabetical by
name), listing its current allies and current wars ("None" if neither) - settled relations only,
not pending alliance proposals. Faction names are HTML-escaped before being written, since
they're player-chosen text going into a file a browser will parse.

Regenerated (not just on-demand) after every action that actually changes what it would show:
faction create, faction disband, declare war, make peace, accept alliance, break alliance - each
of those calls `DiplomacyReportWriter.write(server)` at the end of its success path in
`FactionEventHandler` (not on failed/no-op attempts). Also regenerated once on server start (a
`ServerStartedEvent` subscriber on `DiplomacyReportWriter` itself, keeping this file-writing
concern self-contained rather than adding an event subscription to `FactionEventHandler`), so
the file exists and is current even before anything happens in a given session. A write failure
(disk full, permissions) is logged and swallowed - it must never crash or block the action that
triggered it.

Needs one small addition to `FactionsData`: an `all()` accessor returning every known `Faction`
(nothing currently enumerates them - every existing method looks up one faction at a time).

## Public API

A new `ShadiomFactionAPI` (package `faction`, same public-facade role as `ShadiomNameAPI`/
`ShadiomTitleAPI` in their packages - delegates to the package-private storage classes, which
stay package-private themselves) exposing **read-only** queries:

- Membership: `hasFaction(ServerPlayer)`, `factionOf(ServerPlayer)` (name or null),
  `factionIdOf(ServerPlayer)`, `roleOf(ServerPlayer)` (`"LEADER"`/`"OFFICER"`/`"MEMBER"`/null),
  `leaderOf(MinecraftServer, factionId)` (UUID or null), `memberIdsOf(MinecraftServer,
  factionId)` (`List<UUID>` - raw UUIDs rather than names, since offline members' names aren't
  always resolvable, the same gap already noted in the membership spec; callers that need names
  resolve them however suits their use case).
- Directory: `allFactionIds(MinecraftServer)`, `factionName(MinecraftServer, factionId)`.
- Claims: `claimOwner(MinecraftServer, ResourceKey<Level>, ChunkPos)` (factionId or null),
  `isCapital(MinecraftServer, ResourceKey<Level>, ChunkPos)`.
- Diplomacy: `relationBetween(MinecraftServer, factionIdA, factionIdB)` (`"NEUTRAL"`/`"ALLY"`/
  `"WAR"`), `areAllied(...)`, `areAtWar(...)`.

No mutation methods (create/kick/declare war/etc.) in v1 - those already exist as the in-game GUI
flow with permission checks tied to the acting player; a programmatic mutation surface raises a
separate question (who's the "actor" for permission purposes when called from arbitrary code?)
that nothing has asked for yet. Straightforward to add later against a concrete integration need.

## Error handling

Identical stance to every other action in this mod: server re-validates everything regardless of
what the client's snapshot showed (actor is still the leader, target faction still exists, the
requested transition is still valid), cancels/no-ops silently otherwise.

## Testing

No test harness exists in this repo - verification is `./gradlew compileJava` per task plus an
in-game smoke test: two factions, declare war (both sides see WAR), make peace (back to
NEUTRAL), propose alliance (target sees the incoming proposal), accept (both sides see ALLY),
break alliance unilaterally (back to NEUTRAL), declare war while allied (overrides straight to
WAR), disband a faction with active relations and confirm the other faction's list no longer
shows it, and a server restart to confirm relations persist. Also confirm
`<world save folder>/ShadiomRP/diplomacy.html` exists after server start and updates (open it in
a browser) after each of the above relation changes.

## Out of scope

- PvP protection/friendly-fire changes of any kind.
- Claim-protection interaction (no special access for enemies/allies inside claimed territory).
- More than two-faction relations (no coalitions/blocs, no relation "levels" beyond the three
  states).
- A public feed/announcement when relations change (e.g. server-wide "X declared war on Y" chat
  message) - not asked for, easy to add later if wanted.
- Mutation methods on `ShadiomFactionAPI` (see Public API above).
- Any interactivity in the HTML report (it's a static snapshot regenerated on change, not a live
  page) or serving it over HTTP - it's a local file, opened by hand.
