package net.townymap.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;

public final class TownyMapCommand {

    private TownyMapCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("townymap")
                    .then(toggle("towns", "Town borders",
                        val -> { TownyMapMod.getConfig().townsEnabled   = val; }))
                    .then(toggle("players", "Online players",
                        val -> { TownyMapMod.getConfig().playersEnabled = val; }))
                    .then(toggle("squaremap-background", "Squaremap background",
                        val -> { TownyMapMod.getConfig().squaremapBackgroundEnabled = val; }))
                    .then(ClientCommandManager.literal("refresh")
                        .executes(ctx -> {
                            TownyMapMod.forceRefreshTownClaims();
                            ctx.getSource().sendFeedback(
                                Text.literal("[TownyMap] ")
                                    .formatted(Formatting.GOLD)
                                    .append(Text.literal("Refreshing towns and claims from squaremap...")
                                        .formatted(Formatting.WHITE))
                            );
                            return 1;
                        }))
            );
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> toggle(
            String name, String label, java.util.function.Consumer<Boolean> setter) {

        return ClientCommandManager.literal(name)
                .then(ClientCommandManager.argument("value", BoolArgumentType.bool())
                    .executes(ctx -> {
                        boolean val = BoolArgumentType.getBool(ctx, "value");
                        setter.accept(val);
                        TownyMapMod.getConfig().save();
                        ctx.getSource().sendFeedback(
                            Text.literal("[TownyMap] ")
                                .formatted(Formatting.GOLD)
                                .append(Text.literal(label + ": ")
                                    .formatted(Formatting.WHITE))
                                .append(Text.literal(val ? "enabled" : "disabled")
                                    .formatted(val ? Formatting.GREEN : Formatting.RED))
                        );
                        return 1;
                    }));
    }
}
