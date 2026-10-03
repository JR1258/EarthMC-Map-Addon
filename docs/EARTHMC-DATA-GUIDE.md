# Working with EarthMC data: squaremap and the EarthMC API

A practical reference for building tools against EarthMC. Everything here was verified against live
endpoints while building a Minecraft client mod; the "gotchas" are all mistakes that were actually
made and cost real debugging time.

There are **two independent data sources**. Choosing the right one for a given field is the single
biggest factor in whether your tool is fast or unusable.

| | squaremap | EarthMC API |
|---|---|---|
| Base | `https://map.earthmc.net` | `https://api.earthmc.net/v4` |
| Auth | none | none |
| Shape | a few big files | per-entity queries, batched |
| Best for | geometry, bulk town facts, online players | balances, timestamps, rosters, exact limits |

**Rule of thumb: get everything you possibly can from squaremap. Touch the API only for fields
squaremap does not carry, and only for entities the user actually looked at.**

---

## 1. squaremap — bulk data, one request

### Town polygons and bulk town facts

```
GET https://map.earthmc.net/tiles/minecraft_overworld/markers.json
```

One request returns **every town on the server** (~5,500). Structure:

```jsonc
[ { "markers": [ /* ~5,700 entries */ ] } ]   // top-level is a LIST; real data is [0].markers
```

Each marker has `points`, which is a list of **polygons**, each a list of **rings**, each a list of
`{x, z}`:

```
points -> [ polygon ][ ring ][ {x, z} ]
```

- **Ring 0 is the outer boundary. Rings 1+ are holes** — unclaimed pockets the town surrounds.
  Roughly 35–41 towns have genuine holes at any time.
- Fill them with an **even-odd** rule, or scanline all rings together and consume crossings in
  even-odd pairs, so enclosed rings subtract. Filling each ring separately paints holes solid and
  reports unclaimed chunks as claimed.
- **Chunk count** = polygon area / 256, with hole rings subtracted. Shoelace over all rings works.

Each marker also carries HTML you can parse for free:

- `popup` — mayor, resident count, founded date, pvp/public flags, and the **full resident list**
- `tooltip` — the nation, as `(Member of X)` or `(Capital of X)`

That popup roster is the cheapest way to build a `resident -> town` index. It costs nothing extra.

### Online players

```
GET https://map.earthmc.net/tiles/players.json
```

Live positions and names. Note this gives **position and name only** — no town, no nation. Join it
against markers data or the API if you need more.

### Map imagery tiles

```
GET https://map.earthmc.net/tiles/{world}/{zoom}/{x}_{y}.png
```

256×256 PNGs. **A 404 is normal and expected** — squaremap simply has no tile for ungenerated or
empty regions, and every map edge produces them. Treat 404 as "nothing here", never as an error.
Only 403 / 429 / 5xx are real refusals worth logging or surfacing.

### World bounds

X from **−64512 to 64511**, Z from **−32256 to 32255**.

### Sensible refresh cadence

Claims change slowly, players move constantly:

- `markers.json` — every **60 s**
- `players.json` — every **1 s**
- tiles — on demand, cached by zoom

---

## 2. EarthMC API — the part that trips everyone up

### The index endpoints return names and UUIDs ONLY

```
GET https://api.earthmc.net/v4/players    -> [ { "name": "...", "uuid": "..." }, ... ]
GET https://api.earthmc.net/v4/nations    -> same shape
GET https://api.earthmc.net/v4/towns      -> same shape
```

**This is the mistake to avoid.** These are indexes. They contain no balance, no timestamps, no
counts. If you deserialise them into a model that *has* those fields, every one will be zero — and
a leaderboard sorted by them will look completely plausible while ranking nothing but zeros.

It cost three separate rounds of debugging in one project: "gold" ranked zeros, then "join date"
ranked zeros, then the same bug again on nations. **Read the parser, not the model.**

### The queried endpoints return everything

```
POST https://api.earthmc.net/v4/players
POST https://api.earthmc.net/v4/nations
POST https://api.earthmc.net/v4/towns
Content-Type: application/json

{ "query": ["Name1", "Name2", ...] }        // max 100 names per request
```

**The 100-name cap sets the cost of everything.** Ranking N entities costs `ceil(N/100)` requests:

| Entities | Requests | Verdict |
|---|---|---|
| ~390 nations | 4 | trivial, do it whenever |
| ~5,500 towns | ~56 | fine on demand, cache 30 min |
| ~60,000 players | ~600 | only with progressive loading + persistence |

There is no bulk "everything with details" endpoint. This is the floor.

### Useful fields (queried endpoints only)

**Player** — `balance`, `friendCount`, `registered` / registration timestamp, `lastOnline`,
`town`, `nation`, `isMayor`, `isKing`, `isNPC`

**Nation** — `townCount`, `residentCount`, `chunkCount`, `outlawCount`, `allyCount`, `enemyCount`,
`balance`, `timestamps.registered`, nation bonus

**Town** — `numTownBlocks`, **`maxTownBlocks`**, `residents`, `trusted`, `outlaws`,
`timestamps.registered`, `balance`, and **`coordinates`**

**`coordinates.townBlocks` is the claim geometry** — a list of `[chunkX, chunkZ]` pairs, one per
claimed chunk, exactly `numTownBlocks` long (verified October 2026: Etna, 181 blocks, chunk x
327..342 / z -393..-377). `coordinates.homeBlock` is the same shape for the home chunk and
`coordinates.spawn` is a block position with `world`/`x`/`y`/`z`/`pitch`/`yaw`. Multiply a chunk
coordinate by 16 for blocks.

This is the **live** claim shape, and it is the one thing markers.json cannot give you quickly:
squaremap regenerates on its own schedule, so a fresh claim or unclaim shows up here first. One
request covers up to 100 towns, so "re-read the claims for the towns I can see" is a single call.
Do not use it to build the whole map — 5,500 towns is ~56 requests and markers.json does that in
one — but for a handful of towns it beats the map by minutes.

Timestamps arrive as epoch millis under `timestamps`. **Keep the raw millis.** If you only store a
formatted date string you cannot sort chronologically — sorting the string sorts alphabetically and
silently produces a wrong "oldest first" list.

---

## 3. Towny rules worth knowing

**Claim limit.** A town's allowance is `residents × 12` plus a nation bonus banded by the
**nation's** resident count:

| Nation residents | Bonus |
|---|---|
| ≥20 | 10 |
| ≥40 | 30 |
| ≥60 | 50 |
| ≥80 | 60 |
| ≥120 | 80 |
| ≥200 | 100 |

**But prefer `maxTownBlocks` from the API.** It already includes the bonus and any server-side
override, so reconstructing the formula yourself will eventually disagree with what the game says.

**Activity window.** EarthMC considers a resident inactive after **42 days** without a login. The
API's `residentCount` counts *all* residents regardless — it does not apply the 42-day rule. If you
need active counts, filter on each player's last-online yourself.

---

## 4. Alliances and meganations

`breakthebot.sparked.network` exposes alliances (`GET /alliances` for a name→uuid map, `POST` with
`{"name": "..."}` for one record) — **but as of August 2026 its data is wrong**: it served the
United Nations' 22-nation roster for the African Union, and listed only 5 blocs where the real
dataset has 9. Verified by comparing both records directly: identical member sets, different uuids.

`emcstats.bot.nu` is the dataset earthmc-dynmap uses and maps field-for-field
(`Abbreviation`→short name, `Full name`→name, `Nations`→member list). Prefer it.

---

## 5. Engineering rules that matter here

**Throttle and back off properly.** Cap concurrency (a semaphore of ~4 worked well) and retry with
exponential backoff — roughly 0.4s, 0.8s, 1.6s, 3.2s over five attempts. Short retries (~120 ms)
do not outlast a rate limiter: batches quietly exhaust their attempts and vanish, and you end up
with a leaderboard missing thousands of entries and no error anywhere. **Count dropped batches and
log them** — silent partial data is the worst failure mode here.

**Never fail silently on non-200.** A refusal that returns without logging is indistinguishable
from "no data exists". Log it once per status code so a rate limit is visible.

**Publish long sweeps progressively.** If a 600-request sweep only returns when every batch is
done, the UI shows nothing for the entire duration and one stalled batch holds the whole result
hostage. Slice it (~2,000 names) and publish after each slice.

**Track success separately from attempts.** "When did a fetch last *start*" cannot answer "how old
is this data". Under a sustained outage a start-timestamp keeps advancing and everything looks
fresh. Record the last *successful* landing.

**Cache by cost.** Cheap indexes can refresh every few minutes; a 56-request sweep should be
on-demand and cached for tens of minutes, ideally persisted to disk. Never warm an expensive sweep
for a feature the user has not opened.

**Re-query only what can change.** After a full sweep you know everyone's last-online; subsequent
refreshes need only players active in the last 42 days. Merge results over the cached set rather
than replacing it.

---

## 6. Quick decision table

| You want | Source | Cost |
|---|---|---|
| Town shapes / chunk counts (all towns) | markers.json | 1 request |
| Town shape, live, ahead of the map | POST /towns -> `coordinates.townBlocks` | 1 per 100 towns |
| Town mayor / residents / founded / flags | markers.json popup HTML | free |
| Who is online, and where | players.json | 1 request, 1 s cadence |
| Map imagery | tiles | on demand |
| Nation balances, outlaws, founded | POST /nations | ~4 requests |
| Town balances, outlaws, trusted | POST /towns | ~56 requests |
| Player balance, join date, friends | POST /players | ~600 requests |
| A town's real claim limit | POST /towns → `maxTownBlocks` | 1 per town |
| Alliances | emcstats.bot.nu | 1 request |

Verified August 2026. Endpoint shapes can change — if numeric fields come back as zeros, check
whether you are reading an index endpoint before assuming the data is missing.
