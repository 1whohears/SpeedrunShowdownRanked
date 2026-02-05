package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.errorMsg;

public class LobbyStatus {
    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("status")
                .requires(source -> true)
                .executes(context -> {
                    if (!(context.getSource() instanceof Player player)) {
                        context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                        return 0;
                    }
                    plugin.sendGameplayStatus(player);
                    return Command.SINGLE_SUCCESS;
                })
                .build();
        return new BrigadierCommand(main);
    }
}
