package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.GPServerState;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.SRSDR;
import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.errorMsg;

public class CheckOutQueue {
    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("check_out_queue")
                .requires(source -> true)
                .then(BrigadierCommand.requiredArgumentBuilder("queue_id", IntegerArgumentType.integer(0))
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player player)) {
                                context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                                return 0;
                            }
                            int id = IntegerArgumentType.getInteger(context, "queue_id");
                            GPServerState state = plugin.getServerStateByQueueId(id);
                            if (state == null) {
                                context.getSource().sendMessage(errorMsg("There is no Queue with ID "+id));
                                return 0;
                            }
                            if (state.checkOut(plugin, player)) return Command.SINGLE_SUCCESS;
                            return 0;
                        })).build();
        return new BrigadierCommand(main);
    }
    public static BrigadierCommand create2(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("check_out")
                .requires(source -> true)
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player player)) {
                                context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                                return 0;
                            }
                            GPServerState state = SRSDR.getFromWatchList(player);
                            if (state == null) {
                                context.getSource().sendMessage(errorMsg("You are not part of any match to check out."));
                                return 0;
                            }
                            if (state.checkOut(plugin, player)) return Command.SINGLE_SUCCESS;
                            return 0;
                        }).build();
        return new BrigadierCommand(main);
    }
}
