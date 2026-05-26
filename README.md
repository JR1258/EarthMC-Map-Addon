EarthMC Map Addon is a client-side EarthMC map extension for Xaero’s World Map and Xaero’s Minimap. It overlays EarthMC Towny data directly onto your in-game maps, making it much easier to read towns, nations, players, borders, claims, and useful Towny status information without leaving Minecraft.

## Features

- Displays EarthMC town claims on Xaero’s World Map.
- Optional Squaremap background layer for a fuller EarthMC map view.
- Right-click town info popup with mayor, nation, residents, gold, town size, open/public status, founded date, and overclaim warning.
- Search for towns, nations, and players from the map UI.
- Nation search includes capital, king, residents, nation bonus, chunk count, balance, outlaws, and enemies.
- Player search can show online, offline, hidden, last-known, town, nation, and role information when available.
- Favorite towns and quickly jump back to them from the map toolbar.
- Create Xaero waypoint routes to towns and selected map targets.
- Online player dots on the world map and minimap, colored by relation:
  - Town members
  - Nation members
  - Other players
- Configurable player name and town/nation label visibility ranges.
- Nation capital stars.
- Real-world country and state border overlays for EarthMC.
- Configurable border thickness and town fill opacity.
- Town status map modes, including public towns, overclaimed towns, open towns, towns for sale, and towns without nations.
- Optional RGB/custom-color status highlighting.
- Persistent multi-group chunk counter for planning claims or regions.
- Right-click drag selection for chunk counting.
- Minimap town overlays, town names, player dots, chunk grid options, and Squaremap support.
- Wilderness player alert for nearby outsiders on the minimap.
- Optional hiding of the minimap in the Nether.
- Mod Menu settings screen with organized toggles and sliders.
- EarthMC-only mode so the addon only activates on servers containing `earthmc.net`.
- `/townymap refresh` command to manually refresh town claims.
- Client commands for quickly toggling towns, players, and Squaremap background.

## Commands

- `/townymap refresh`  
  Refreshes town claims from EarthMC/Squaremap.

- `/townymap towns true|false`  
  Enables or disables town borders.

- `/townymap players true|false`  
  Enables or disables online player rendering.

- `/townymap squaremap-background true|false`  
  Enables or disables the Squaremap background layer.

## Notes

This mod is made specifically for EarthMC and is designed to work with Xaero’s World Map and Xaero’s Minimap. Most data is pulled from EarthMC’s public map/API data and cached or refreshed automatically while playing.
