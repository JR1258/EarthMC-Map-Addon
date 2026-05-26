# libs/ — Xaero's Mod JARs

Place the Fabric 1.21.1 versions of these two mods here before building:

| Mod | CurseForge page |
|-----|-----------------|
| Xaero's World Map | https://www.curseforge.com/minecraft/mc-mods/xaeros-world-map |
| Xaero's Minimap   | https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap  |

Download the **Fabric** variants for **Minecraft 1.21.1**.  
Example filenames:

```
XaerosWorldMap_24.1.2_Fabric_1.21.1.jar
Xaeros_Minimap_24.2.0_Fabric_1.21.1.jar
```

These are `modCompileOnly` — they are used only at compile time and should
**not** be bundled into the output JAR.  Users must install Xaero's mods
separately as usual.

## Finding the correct field names

If town outlines or players don't appear, open
`~/.minecraft/config/townymapaddon.json` and verify that
`xaeroCameraXField`, `xaeroCameraZField`, and `xaeroBlockScaleField`
match the actual field names in your version of Xaero's WorldMap.

Enable DEBUG logging (`log4j.logger.TownyMapAddon=DEBUG`) to see every field
that the mod discovers when GuiMap is opened.
