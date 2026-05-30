package net.townymap.input;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.resources.Identifier;
import net.townymap.TownyMapMod;
import org.lwjgl.glfw.GLFW;

public final class TownyMapKeybinds {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("townymapaddon", "keybinds"));

    private static KeyMapping toggleSquaremap;
    private static KeyMapping cycleBorders;
    private static KeyMapping cycleMapMode;
    private static KeyMapping toggleChunkCounter;
    private static KeyMapping refreshTowns;

    private TownyMapKeybinds() {
    }

    public static void register() {
        toggleSquaremap = register("toggle_squaremap");
        cycleBorders = register("cycle_borders");
        cycleMapMode = register("cycle_map_mode");
        toggleChunkCounter = register("toggle_chunk_counter");
        refreshTowns = register("refresh_towns");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleSquaremap.consumeClick()) TownyMapMod.toggleSquaremapBackground();
            while (cycleBorders.consumeClick()) TownyMapMod.cycleBorderOverlayMode();
            while (cycleMapMode.consumeClick()) TownyMapMod.cycleTownStatusOverlayMode();
            while (toggleChunkCounter.consumeClick()) TownyMapMod.toggleChunkCounter();
            while (refreshTowns.consumeClick()) TownyMapMod.refreshTownClaimsFromKeybind();
        });
    }

    private static KeyMapping register(String id) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.townymapaddon." + id,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        ));
    }
}
