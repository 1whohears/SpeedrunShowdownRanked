package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.QueueType;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.errorMsg;

public class JoinQueue {
    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("join_queue")
                .requires(source -> true)
                .then(BrigadierCommand.literalArgumentBuilder("solo")
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player player)) {
                                context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                                return 0;
                            }
                            plugin.joinCreateQueue(player, QueueType.SOLO);
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(BrigadierCommand.literalArgumentBuilder("duos")
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player player)) {
                                context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                                return 0;
                            }
                            plugin.joinCreateQueue(player, QueueType.DUOS);
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(BrigadierCommand.literalArgumentBuilder("team")
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player player)) {
                                context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                                return 0;
                            }
                            plugin.joinCreateQueue(player, QueueType.TEAM);
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .build();
        return new BrigadierCommand(main);
    }
}
