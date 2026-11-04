# 1.4.11

**Waypoints**
- Xaero's waypoints are visible on the world map again. With the Squaremap layer on, the EarthMC imagery painted straight over every marker on the map, so the addon now draws them back on top itself, at Xaero's own size and in Xaero's own colours. Hovering one still raises Xaero's tooltip and right-clicking one still opens Xaero's menu, because those were never covered.
- Right-clicking empty map opens Xaero's own menu again, so you can add a waypoint, teleport or share a location from the map. Right-clicking a town still opens the town panel; unclaimed ground is Xaero's, and so is any waypoint or tracked player under the cursor, so a waypoint standing inside a town can be edited, moved or deleted again. Settings -> World Map -> Wilderness Town Info puts the old behaviour back if you preferred it.
- Waypoints no longer disappear from the enlarged minimap, or on frames where the EarthMC layer drew nothing.
- On 1.21.11 only: your waypoints, the player arrow and the waypoint set name no longer show up on a map of a world you are not standing in, usually the Moon. The three hooks that suppress them named the method using the newer Minecraft versions' name for it, so on that build they had never once run.

**Nations**
- Every nation reported "No capital". The capital and king fields stopped being requested in 1.4.10, which took the capital stars, the "Capital of" tag, the capital circle in join range, the Capital and King rows and every nation jump with them.
- Searching a nation now goes to its capital, framed and flashed like any other town, instead of trying to fit the whole nation on screen.

**Ice highways**
- The ice highway network is built in, as mode 7 on the Map button. It is XiLeF2211's community-maintained map, fetched live so it stays current, and only downloaded once you switch the layer on.

**Fixes**
- A Moon outpost is no longer wiped out by an Earth claim refresh. Every Moon town shares a name with an Earth one, and the two were being confused: the Moon claim vanished and right-clicking it said wilderness.
- Leaving the map in Planning mode survives a restart. It silently came back as None every time.
- The chunk counter's Reset button sits beside the counter instead of a row above it.

**Search flash**
- A searched town now blinks its own border rather than tracing a second outline a pixel or two beside it, and it works again while a map mode is on. The freeze when the blink stopped is gone.

**Behind the scenes**
- The live player feed now reads EarthMC's new endpoint, which replaced the file the mod used to fetch. No visible difference, beyond it revalidating properly: most polls now transfer nothing instead of a few KB.

Available for Minecraft **1.21.11**, **26.1.x**, **26.2** and **26.3**.
