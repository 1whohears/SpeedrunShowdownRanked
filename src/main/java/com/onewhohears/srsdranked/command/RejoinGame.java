package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.GPServerState;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.SRSDR;
import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.errorMsg;

public class RejoinGame {
    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("rejoin")
                .requires(source -> true)
                .executes(context -> {
                    if (!(context.getSource() instanceof Player player)) {
                        context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                        return 0;
                    }
                    GPServerState state = SRSDR.getFromWatchList(player);
                    if (state == null) {
                        context.getSource().sendMessage(errorMsg("You are not part of any match to rejoin."));
                        return 0;
                    }
                    SRSDR.sendToGameplayServer(player, state.getLobbyId());
                    return Command.SINGLE_SUCCESS;
                })
                .build();
        return new BrigadierCommand(main);
    }
}
