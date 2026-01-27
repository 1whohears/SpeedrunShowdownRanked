package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.GPServerState;
import com.onewhohears.srsdranked.QueueType;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.*;

public class CheckInQueue {
    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("check_in_queue")
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
                            if (state.checkIn(plugin, player)) return Command.SINGLE_SUCCESS;
                            return 0;
                        })).build();
        return new BrigadierCommand(main);
    }
}
