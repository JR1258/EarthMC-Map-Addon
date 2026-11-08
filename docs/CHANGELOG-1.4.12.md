# 1.4.12

**Nations**
- A town's nation is no longer given as "Capital of <nation>" in places that wanted the nation's name. The map's tooltips word a capital's nation that way and the addon stored it exactly as it arrived, so for every nation that has a capital: the card and the Expand page for a player the API has nothing for read "Nation: Capital of Switzerland", the label under their dot on the map read "Bern / Capital of Switzerland", and the nation link on that card led nowhere. The capital is still said in words where it belongs, on the town hover and the right-click title, and the card now says it the way the rest of the mod does -- "Capital of:" as the label, with the nation's own name beside it.
- The Stats nation tables counted every nation with a capital twice. "Switzerland" and "Capital of Switzerland" were two different rows, so that nation's towns, residents and chunks were split between them and the nation total came out too high.
- Players living in your nation's capital are coloured as nation-mates again. The same mismatch meant a capital's resident never compared equal to anyone else in the nation, so they were drawn as strangers unless you lived in the capital yourself.

**Other servers**
- The world map is no longer black on a server the addon has nothing to do with. With "EarthMC Only" on, which is the default, nothing of the addon is drawn on another server and it says so in the log -- but it was still pointing Xaero's world map at whatever world it had worked out, every time the map was opened. On a server with more than one world that is the overworld, which on a build or creative server is usually the hub, so Xaero was moved off the world it had spent the session mapping and onto an empty one. It now leaves Xaero's map alone on any server it is idle on.
- The addon also no longer moves Xaero's map to a world it only guessed at. It recognises the worlds the map publishes by name; anything else was being treated as the overworld, which is right for the Nether and the End but also catches every extra world a server adds, since those are named the same way. When the world cannot be recognised, Xaero keeps showing the dimension you are standing in, and its own dimension button and the Earth/Moon setting still override that. The log now names the world when this happens.

**Fixes**
- In the Nether or the End, Xaero's map shows the Nether or the End. It was being moved to the overworld, so with the overlay hidden out there, which is the default, the map showed overworld ground you were not standing on. In "Overworld Coords" mode the EarthMC overlay now has the real Nether tiles underneath it to line up with, which is what that mode was for.

Available for Minecraft **1.21.11**, **26.1.x**, **26.2** and **26.3**.
