# 1.4.12

**EarthMC API key**
- EarthMC now requires an API key on its API, as of 19 November. Run `/api key create` in game and paste it into Settings -> Advanced -> EarthMC API Key. It is sent only to api.earthmc.net, is never written to the log, and survives "Reset All".
- Without one, everything the live map carries still works: borders, nation colours, town names, players, the imagery layer, hover, search, nation panels and the capital stars.

**Towns**
- Town cards now work without a key, from the town endpoint EarthMC published the same day: mayor, nation, board, founded, PVP, Public, the full resident roll, bank balance and its own claim count. Only Open / For sale, the claim limit and the active resident count still need one.
- Right-clicking a town always pulls its current data instead of reusing a card up to a minute old, and cards no longer flash a shorter version before the real one arrives.
- The residents line leads with the active count -- "Residents: 30 (Inactive: 102)" -- which is the figure that earns chunks, and a town's size counts its Moon claims again.

**Search**
- Your last ten searches sit under the search bar, newest first, and lead to wherever that town, nation or player is now. "Reset All" no longer clears them.

**Performance**
- Took the measured waste off the minimap, hover and search paths.
- Live player positions now come from the map, spending no API budget. Caches no longer grow for a whole session or keep towns that have left the map.

**Fixes**
- A blank EarthMC imagery layer now says which stage is failing instead of going dark in silence.
- The "Xaero's World Map is incompatible" warning no longer fires when it is not, and can now fire for the one part that actually breaks.
- A town's nation is no longer stored as "Capital of X", which had split nations into two rows in Stats, mis-coloured capital residents and broken nation links.
- On a server the addon is idle on it leaves Xaero's map alone instead of moving it to another world, and the Nether and End show the Nether and End.
- The minimap no longer draws a second player arrow on top of Xaero's by default.

Available for Minecraft **1.21.11**, **26.1.x**, **26.2** and **26.3**.
