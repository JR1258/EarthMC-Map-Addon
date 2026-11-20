# 1.4.13

**EarthMC API key**
- You no longer have to copy the key across yourself. Settings -> API Key -> "Get key from chat" opens chat with `/api key copy` already written out; press Enter and the key is saved the moment EarthMC answers. It works even when EarthMC hands the key over as a click-to-copy line, where the key is never printed on screen at all.
- Never made one? The addon waits, sees nothing come back, and offers `/api key create` as a click. It never sends either command for you -- they act on your account, so pressing Enter stays yours.
- "Copy API Key" puts the stored key back on your clipboard, and the key now has its own settings category instead of sitting at the bottom of Advanced.
- Everywhere the addon used to say "run `/api key create`" -- the startup warning, a key EarthMC refused, a key that pasted short -- it now says `/api key copy`, which is the one that answers the question actually being asked.

**Without a key**
- The expanded panel opens for towns and nations now. Right-clicking a town showed its card while pressing Expand on that same card said "Nothing found"; both work.
- Towns show mayor, nation, founded, board, claims, bank and resident count. Nations show their capital, town count, claimed chunks, alliances and the full town list, read from the map rather than fetched.
- What EarthMC serves only with a key is left out rather than drawn empty.

**Fixes**
- The first-launch notice no longer disappears during the join -- and no longer counts that as having been shown. It returns until you have actually read it, and "What A Key Adds" in settings reopens it any time.
- Its buttons are visible. They were being drawn underneath the notice's own panel, on that screen and on the access-removed one.

Available for Minecraft **1.21.11**, **26.1.x**, **26.2** and **26.3**.
