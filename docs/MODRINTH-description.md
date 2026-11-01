# EarthMC Map Addon

A client-side EarthMC map extension for **Xaero's World Map** and **Xaero's Minimap**. It overlays EarthMC Towny data directly onto your in-game maps so you can read towns, nations, players, borders, and claims without leaving Minecraft.

## Worlds

- Terra Nostra and the Moon, each with its own claims, outposts, terrain and players.
- The World button follows the dimension you are in by default: the Moon while you are up there, Terra Nostra everywhere else. Click it to pin one world instead, and cycle round to Auto to hand it back.
- Browsing one world leaves the minimap on the other, so it keeps showing the claims, terrain, players and chunk selection where you actually are.
- The world map switches Xaero's own dimension to match, so you see the lunar terrain you have explored, and hands it back when the map closes.
- Outpost sizes are counted separately from the parent town, so a small lunar outpost is not reported as its town's whole claim. Popups, tooltips and search show the claim in front of you with the town-wide total underneath.

## World Map

- Town claims drawn as real claim shapes, with configurable border thickness, fill opacity, and smooth outlines.
- Squaremap background layer — the real map.earthmc.net tiles rendered under the overlay, with an optional darken pass.
- Nation capital stars, toggleable.
- Nation join range — circles showing where a town could join a nation (1,500 block town radius, 5,000 nation radius), with a union outline when nearby towns extend the nation's reach.
- World Map Overview — zooms out past Xaero's normal limit to see the whole world.
  - Far-zoom town dots so towns stay visible when zoomed out.
- Real-world country and state borders as an overlay.
- Custom overlays — drop GeoJSON FeatureCollections (LineString, MultiLineString, Point) into a folder the mod creates and opens for you.

## Minimap

- Town borders, town names, and player dots on the minimap.
- Info panel under the minimap: current town and nation, nearby players with live distance (players leaving view linger in red at their last-known spot), nearest town when in wilderness. Each line toggles independently and stacks below Xaero's own info without overlapping.
- Wilderness player alert for nearby outsiders.
- Optional hide-in-Nether, or an "EarthMC map in Nether" mode that scales the overworld map ×8 over Nether tiles.

## Map modes

- Seven map modes: Public, Overclaim, Open, Meganations, Alliances, Planning, and Ice Highways. The first three highlight matching towns in a colour you pick (fixed or RGB cycling); Meganations and Alliances recolour towns by bloc instead, and use the BreakTheBot Alliance API. Ice Highways draws EarthMC's ice highway network over the map, using XiLeF2211's community-maintained [Ice Highways Map](https://xilef2211.github.io/ice-highways-map/) data, fetched only while that mode is selected.
- Planning mode turns the search bar into a nation picker and lets you place prospective towns (T1, T2, …) with numbered map markers. Towns outside the nation's range are marked red on both the chip and the map.

## Search

- Towns, nations, players, and alliances, with detail panels — nation bonus, king/capital, outlaws, enemies, resident lists, player online/last-seen/town/nation/role.
- Filters: `nationless`, `n:germany,france` (multi-value), `r>30,<60`, `chunks>500`. Short prefixes `n:` and `r:` supported.
- Archive lookup — type a `dd/mm/yyyy` date to load historical map data from the Wayback Machine.
  - Earliest date is 17/4/2026, as that is when Nostra started.
  - The separator can be `/`, `.`, `,`, `-` or a space, leading zeroes are optional, and the year can be 2 or 4 digits (`17 4 26` works).
- Filters persist while panning; the box auto-widens.
- Favourites for towns, nations, players, and alliances — jump to a nation's capital or an online player's location.

## Players

- Online player dots on both maps coloured by relation (your town / your nation / others), real player heads, configurable head/name/label ranges, and last-seen positions.

## Info Panel

- Press **J**, or the expand button beside the world map search bar.
- **Dashboard** — your town and nation at a glance: claimed chunks against your limit, balance, residents, plus server totals, your favourites, and how old the claim data is.
- **Statistics** — ranked lists of towns and nations by residents, chunks, gold, outlaws and founding date. Every name opens its full page.
- Its own search bar covering towns, nations and players at once, ranked so the closest match comes first.

## Info panels

- Right-click a town for mayor, nation, residents, gold, chunk count, open/public status, and founded date.
- Click on a nation star for information such as the capital, founded date, nation chunks, towns, etc.
- Click on a player head or dot to get information on a player.
- All three of the above have an extended mode with more in-depth information.

## Tools

- Chunk counter with persistent multi-group tracking and Shift+right-drag box select.
- Xaero waypoint routes to map targets.
- A line under the world map coordinates showing how old the claim data is, with a button to reload it. Turns amber when overdue, red if a refresh failed.
- Map screenshots on a keybind — captures the squaremap, town, and player layers, hides mod and Xaero UI, with its own settings section (players off, nation stars on, dimmed towns hidden by default) and no chunk cursor.

## Setup

- Searchable sectioned settings screen via Mod Menu with per-row Reset and right-click-to-go-back; UI scale; dark buttons; EarthMC-only mode; keybinds for the Info Panel (**J**), reloading claims (**R**, works with the map open) and map screenshots — all rebindable in the settings or in Options > Controls, and synced with both.

## Requirements

- Fabric Loader + Fabric API
- Xaero's Minimap and/or Xaero's World Map
- Mod Menu (optional, for the in-game settings screen)
