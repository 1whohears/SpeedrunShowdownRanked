package com.onewhohears.srsdranked.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.onewhohears.srsdranked.SpeedrunShowdownRanked;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;

import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.SRSDR;
import static com.onewhohears.srsdranked.SpeedrunShowdownRanked.errorMsg;

public final class LinkDiscord {

    public static BrigadierCommand create(final SpeedrunShowdownRanked plugin) {
        LiteralCommandNode<CommandSource> main = BrigadierCommand.literalArgumentBuilder("link_discord")
                .requires(source -> true)
                .then(BrigadierCommand.requiredArgumentBuilder("linkCode", IntegerArgumentType.integer(0, 999999))
                        .executes(context -> {
                            if (!(context.getSource() instanceof Player player)) {
                                context.getSource().sendMessage(errorMsg("User of this command must be a player!"));
                                return 0;
                            }
                            int linkCode = IntegerArgumentType.getInteger(context, "linkCode");
                            if (SRSDR.linkDiscordAccount(player, linkCode)) return Command.SINGLE_SUCCESS;
                            return 0;
                        })
                ).build();
        return new BrigadierCommand(main);
    }
}
