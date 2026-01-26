package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import java.util.Optional;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.CONFIG;
import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.errorMsg;

public class VetoSeed {
    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("veto")
                .requires(source -> true)
                .executes(context -> {
                    if (!(context.getSource() instanceof Player player)) {
                        context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                        return 0;
                    }
                    Optional<ServerConnection> optionalServerConnection = player.getCurrentServer();
                    if (optionalServerConnection.isEmpty()) {
                        context.getSource().sendMessage(errorMsg("Player not in a server!"));
                        return 0;
                    }
                    ServerConnection server = optionalServerConnection.get();
                    String serverName = server.getServerInfo().getName();
                    if (serverName.equals(CONFIG.getString("default_server"))) {
                        context.getSource().sendMessage(errorMsg("Cannot Veto in the Lobby!"));
                        return 0;
                    }
                    String gamePrefix = CONFIG.getString("gameplay_server");
                    if (!serverName.startsWith(gamePrefix)) {
                        context.getSource().sendMessage(errorMsg("You can only veto in a Game Server!"));
                        return 0;
                    }
                    String gameIdStr = serverName.substring(gamePrefix.length());
                    int gameId;
                    try {
                        gameId = Integer.parseInt(gameIdStr);
                    } catch (NumberFormatException e) {
                        context.getSource().sendMessage(errorMsg("Your server name "+serverName
                                +" does not end in a number "+gameIdStr));
                        return 0;
                    }
                    if (plugin.vetoSeed(player, gameId)) return Command.SINGLE_SUCCESS;
                    return 0;
                })
                .build();
        return new BrigadierCommand(main);
    }
}
