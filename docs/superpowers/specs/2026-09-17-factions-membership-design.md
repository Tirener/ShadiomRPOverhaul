# Factions: Membership & Roles (v1 — "basics")

Sub-project 1 of 3 in the factions effort (land claims and diplomacy follow once this exists,
since both depend on factions/membership existing). Scope for this spec is exactly: create a
faction, invite/accept/decline, kick, promote/demote, leave, disband — all driven through a
player-facing GUI, not chat commands. No land claims, no diplomacy, no faction identity in chat
or on the nameplate (explicitly deferred, not part of this pass).

## Roles

Three tiers, flat (no custom rank names):

- **Leader** — exactly one per faction. Can invite, kick anyone but themselves, promote a member
  to officer, demote an officer to member, and disband the faction.
- **Officer** — can invite, and can kick members only (not other officers or the leader).
- **Member** — can leave. No management powers.

No leadership transfer in v1. The only way out for a leader is disbanding. A player is in at
most one faction at a time.

Faction records store UUIDs, not names, so an offline member's display name isn't resolvable
without a profile-cache lookup — out of scope for v1. Membership itself persists across logout
(an offline member's UUID stays in their role's set), but the GUI's member list and every
target-resolving action (invite/kick/promote/demote) only operate on the currently-online
subset: offline members are simply absent from the list until they reconnect, not shown as
"offline" rows. Acceptable for v1 since managing someone who isn't there to see the result is a
rare case; revisit with a profile-cache lookup if that proves annoying in practice.

Joining is invite-only: a leader or officer invites a currently-online player by name; there is
no public faction list or join-request flow in v1.

## Data model

New `faction` package, following the existing `names` package's storage split
(`NamesData`/`NamePlayerData`):

- `Faction` — `id` (lowercased, unique slug derived from the faction name — same
  case-insensitive collision handling as `NamesData.comboKey`), `name` (as-typed display
  spelling), `leader` (UUID), `officers` (`Set<UUID>`), `members` (`Set<UUID>`, plain members
  only — leader and officers are tracked in their own fields, not duplicated into `members`).
- `FactionsData` (`SavedData`, world-level, same `computeIfAbsent` pattern as `NamesData.get`) —
  holds:
  - `factions`: `Map<String factionId, Faction>`
  - `memberIndex`: `Map<UUID, String factionId>` — reverse lookup covering the leader, every
    officer, and every plain member (all three sets), kept in sync on every membership change so
    "what faction is this player in" is O(1) instead of a scan.
  - `pendingInvites`: `Map<UUID invitee, Set<String factionId>>`

No public `ShadiomFactionAPI` class in this pass — nothing else in the mod needs to consume
faction membership yet, unlike names/titles which are consumed by chat formatting today. Add an
API surface when land claims or diplomacy (or an external mod) actually need to read faction
membership.

## Server-side rules handler

A static `FactionEventHandler`-style class (mirroring `NameEventHandler`'s role for names) holds
the actual create/invite/accept/decline/kick/promote/demote/leave/disband logic, validates the
actor's role against the rules above, mutates `FactionsData`, and is the single place
`FactionActionC2SPacket`'s handler calls into. `FactionsData` itself stays storage-only
(get/set/serialize), same division of responsibility as `NamesData` vs `NameEventHandler`.

## GUI

One `FactionScreen` (client-side), built the same way as the existing `NamePickerScreen` —
vanilla `Button`/`AbstractWidget` only, no custom textures — but branching internally on state
rather than being split into multiple screen classes:

- **No faction**: a text field + Create button, and a list of pending invites (faction name +
  Accept/Decline per row).
- **Has faction**: member list (name + role tag), with per-row action buttons scoped to the
  viewer's own role (Kick/Promote/Demote shown only where the rules above allow it), an invite
  panel listing currently-online players who aren't already in this faction, and a Leave button
  (members/officers) or Disband button (leader).

## Networking

Two packets, not one per action — nine similar small actions would be a lot of boilerplate for
what's really one dispatch:

- `OpenFactionScreenS2CPacket` — carries a full snapshot: whether the player has a faction, its
  name + member list with roles, the viewer's own role, pending invites (if none), and the list
  of invitable online players (if in a faction). Used both to open the screen and to refresh it:
  after every successful action, the server resends this to the acting player so their screen
  reflects the new state. Other online faction members only see changes next time they open
  their own screen — no live broadcast to everyone on every action; that's more plumbing than
  v1 needs.
- `FactionActionC2SPacket(action, arg)` — one packet, server-side switch dispatches to
  `FactionEventHandler`. `action` is an enum: `CREATE, INVITE, ACCEPT, DECLINE, KICK, PROMOTE,
  DEMOTE, LEAVE, DISBAND`. `arg` is a faction name (CREATE), a player name (INVITE/KICK/
  PROMOTE/DEMOTE — must resolve to a currently-online `ServerPlayer`), a faction id
  (ACCEPT/DECLINE), or unused (LEAVE/DISBAND).

Both packets register on `ModNetwork.CHANNEL` with the next free ids (`ID_NAME_SYNC` is
currently the last one registered — these two continue after it). Same registration pattern as
every existing packet pair in `network/names` and `network/title`.

## Entry point

A new `/faction` command (any player, no permission requirement — distinct from the op-only
`/shadiomrp` config root, since this isn't a config command) sends the initial
`OpenFactionScreenS2CPacket` and opens the screen.

## Error handling

Every action re-validates server-side regardless of what the client's stale snapshot showed
(same defensive stance as `NameEventHandler.handleSubmit`/`attemptApply` re-validating against
current server state rather than trusting the client): actor's role is re-checked against the
rules table above, target player must still be online and eligibility-appropriate (e.g. can't
invite someone already in a faction, can't kick someone no longer a member), faction name
uniqueness re-checked on create. A failed action just re-sends the current (unchanged) snapshot
— no separate error-message channel for v1, matching how small a surface this is; add one if
silent no-ops prove confusing in practice.

## Testing

No test harness exists in this repo (Minecraft mod, needs a live server/client). Verification is
`./gradlew compileJava` plus an in-game smoke test once implemented: create a faction, invite a
second online player, accept, promote to officer, kick, leave, disband — covering every action
in the rules table above.

## Out of scope (future sub-projects)

- Land claims (sub-project 2, depends on this).
- Diplomacy / relations between factions (sub-project 3, depends on this).
- Faction tag/color in chat or on the nameplate.
- Leadership transfer, custom rank names, open/public faction join flow.
