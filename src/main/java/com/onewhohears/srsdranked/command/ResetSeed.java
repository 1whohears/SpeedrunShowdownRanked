package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.CONFIG;
import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.infoMsg;

public final class ResetSeed {

    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("reset_seed")
                .requires(source -> source.hasPermission("speedrunshowdown"))
                .then(BrigadierCommand.requiredArgumentBuilder("gameplay_id",
                                IntegerArgumentType.integer(0, CONFIG.getInt("gameplay_server_num")))
                        .executes(context -> {
                            int id = IntegerArgumentType.getInteger(context, "gameplay_id");
                            boolean result = plugin.resetGameplaySeed(id,
                                    msg -> context.getSource().sendMessage(infoMsg(msg)));
                            return result ? Command.SINGLE_SUCCESS : 0;
                        })).build();
        return new BrigadierCommand(main);
    }
}
