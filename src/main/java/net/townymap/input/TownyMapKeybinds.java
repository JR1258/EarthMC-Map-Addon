package net.townymap.input;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import net.townymap.TownyMapMod;
import org.lwjgl.glfw.GLFW;

public final class TownyMapKeybinds {

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("townymapaddon", "keybinds"));

    private static KeyBinding toggleSquaremap;
    private static KeyBinding cycleBorders;
    private static KeyBinding cycleMapMode;
    private static KeyBinding toggleChunkCounter;
    private static KeyBinding refreshTowns;

    private TownyMapKeybinds() {
    }

    public static void register() {
        toggleSquaremap = register("toggle_squaremap");
        cycleBorders = register("cycle_borders");
        cycleMapMode = register("cycle_map_mode");
        toggleChunkCounter = register("toggle_chunk_counter");
        refreshTowns = register("refresh_towns");

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleSquaremap.wasPressed()) TownyMapMod.toggleSquaremapBackground();
            while (cycleBorders.wasPressed()) TownyMapMod.cycleBorderOverlayMode();
            while (cycleMapMode.wasPressed()) TownyMapMod.cycleTownStatusOverlayMode();
            while (toggleChunkCounter.wasPressed()) TownyMapMod.toggleChunkCounter();
            while (refreshTowns.wasPressed()) TownyMapMod.refreshTownClaimsFromKeybind();
        });
    }

    private static KeyBinding register(String id) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.townymapaddon." + id,
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                CATEGORY
        ));
    }
}
