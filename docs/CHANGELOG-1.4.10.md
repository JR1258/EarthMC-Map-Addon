# 1.4.10

**Performance**
- The periodic stutter is gone. Working out which town owns each visible chunk used to land on one frame every so often; it now runs on a background thread while the previous view keeps drawing.
- Claim outlines draw as merged runs instead of one call per chunk side: a third to a fifth of the geometry in dense areas.

**Less data used**
- The EarthMC API is now asked for compressed responses, and only for the fields that are actually read. Opening the rankings went from 4.7 MB to 133 KB.
- Idle clients back off. Standing still with no map open drops roughly 37 MB an hour to about 6; moving resets it instantly. The wilderness alert is exempt, since idling is the state it exists for.
- The player feed polls slower when only the minimap is up, and not at all when nothing displays it.
- Claims now honour the polling interval the map server asks for, so EarthMC can slow every client down without waiting for a mod update. Requests also identify their version now.

**Search**
- Picking a result zooms to fit it instead of keeping whatever zoom you were already at, and flashes the claim it found so you can pick it out from its neighbours. Nations frame every member claim. Both can be turned off under World Map.

Available for Minecraft **1.21.11**, **26.1.x**, **26.2** and **26.3**.
