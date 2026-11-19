package net.townymap.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;
import net.townymap.api.ApiKeyCapture;

/**
 * Starts the "read my key out of chat" flow, from wherever the player asked for it.
 *
 * <p>Shared rather than duplicated because it is offered from two screens that cannot see each
 * other: the settings row and the first-launch notice. Both owe the player the same consent prompt,
 * the same command and the same promise about what is read, and a second copy of that promise would
 * be a second copy to keep honest.
 */
public final class ApiKeyCaptureFlow {

    /**
     * Always {@code copy}, never {@code create}, and not a guess.
     *
     * <p>EarthMC splits the job three ways: {@code create} mints a first key, {@code copy} hands
     * back one that already exists, {@code delete} is how you rotate. Writing the right one out
     * would mean knowing whether this player has a key -- and nothing here knows that. An empty
     * {@link TownyMapConfig#earthMcApiKey} covers a fresh install, a new machine, a cleared config
     * and a key made for some other tool exactly as well as it covers someone who has never made
     * one, and no keyless endpoint will answer the question either.
     *
     * <p>So it does not guess. {@code copy} is the one command that is safe under every answer: it
     * cannot mint, cannot rotate, and cannot disturb an account that is already set up -- which
     * matters all the more because what {@code create} does to an account that already has a key is
     * undocumented and not in any public source. If no key comes back, that silence is the answer,
     * and {@link TownyMapMod} offers {@code create} on the spot.
     */
    public static final String KEY_COMMAND = "/api key copy";

    private ApiKeyCaptureFlow() {}

    /**
     * Asks first, then opens the window and puts the command in the player's chat box.
     *
     * @param returnTo the screen Cancel goes back to -- whichever one the player asked from, so
     *                 declining leaves them where they were rather than dropping them into the world.
     */
    public static void start(Screen returnTo) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null) return;
        TownyMapConfig cfg = TownyMapMod.getConfig();
        String tail = cfg == null || cfg.earthMcApiKey.isEmpty()
                ? "If you have not made a key yet, EarthMC will say so and the addon will offer "
                        + "you the command that makes one."
                : "Whatever EarthMC hands back replaces the key stored now.";
        mc.setScreen(new ConfirmScreen(
                ok -> {
                    if (!ok) { mc.setScreen(returnTo); return; }
                    ApiKeyCapture.arm();
                    TownyMapMod.announceKeyCaptureArmed(KEY_COMMAND);
                    // Chat, with the command already written out -- all that is left is Enter. It is
                    // not sent for them: these commands act on their account, which is theirs to
                    // trigger, and a command typed into their own chat box is visible, editable and
                    // abandonable in a way one sent on their behalf is not.
                    mc.setScreen(new ChatScreen(KEY_COMMAND, false));
                },
                Text.literal("Read chat to pick up your API key?"),
                Text.literal("For the next " + (ApiKeyCapture.WINDOW_MS / 1000)
                        + " seconds this reads incoming chat, looking only for an EarthMC API key. "
                        + "Chat will open with " + KEY_COMMAND + " already written out -- press Enter "
                        + "to send it -- and the key is saved the moment it appears, whether "
                        + "EarthMC prints it or puts it behind a click-to-copy. " + tail
                        + " Nothing else is read, kept or sent anywhere, and the window closes on "
                        + "its own."),
                Text.literal("Read chat for " + (ApiKeyCapture.WINDOW_MS / 1000) + "s"),
                Text.literal("Cancel")));
    }
}
