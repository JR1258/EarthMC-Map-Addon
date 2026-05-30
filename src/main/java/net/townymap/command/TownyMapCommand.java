package net.townymap.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.townymap.TownyMapConfig;
import net.townymap.TownyMapMod;

public final class TownyMapCommand {

    private TownyMapCommand() {}

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommands.literal("townymap")
                    .then(toggle("towns", "Town borders",
                        val -> { TownyMapMod.getConfig().townsEnabled   = val; }))
                    .then(toggle("players", "Online players",
                        val -> { TownyMapMod.getConfig().playersEnabled = val; }))
                    .then(toggle("squaremap-background", "Squaremap background",
                        val -> { TownyMapMod.getConfig().squaremapBackgroundEnabled = val; }))
                    .then(ClientCommands.literal("refresh")
                        .executes(ctx -> {
                            TownyMapMod.forceRefreshTownClaims();
                            ctx.getSource().sendFeedback(
                                Component.literal("[TownyMap] ")
                                    .withStyle(ChatFormatting.GOLD)
                                    .append(Component.literal("Refreshing towns and claims from squaremap...")
                                        .withStyle(ChatFormatting.WHITE))
                            );
                            return 1;
                        }))
            );
        });
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<
            net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource> toggle(
            String name, String label, java.util.function.Consumer<Boolean> setter) {

        return ClientCommands.literal(name)
                .then(ClientCommands.argument("value", BoolArgumentType.bool())
                    .executes(ctx -> {
                        boolean val = BoolArgumentType.getBool(ctx, "value");
                        setter.accept(val);
                        TownyMapMod.getConfig().save();
                        ctx.getSource().sendFeedback(
                            Component.literal("[TownyMap] ")
                                .withStyle(ChatFormatting.GOLD)
                                .append(Component.literal(label + ": ")
                                    .withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(val ? "enabled" : "disabled")
                                    .withStyle(val ? ChatFormatting.GREEN : ChatFormatting.RED))
                        );
                        return 1;
                    }));
    }
}
