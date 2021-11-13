package dev.emortal.immortal.commands

import dev.emortal.immortal.game.GameManager
import dev.emortal.immortal.game.GameManager.joinGameOrNew
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import world.cepi.kstom.command.arguments.suggest
import world.cepi.kstom.command.kommand.Kommand

object PlayCommand : Kommand({

    onlyPlayers

    val gamemodeArg = ArgumentType.Word("gamemode").map { input: String ->
        GameManager.registeredGameMap.entries.firstOrNull { it.value.gameName == input && it.value.showsInSlashPlay }

    }.suggest {
        GameManager.registeredGameMap.values
            .filter { it.showsInSlashPlay }
            .map { it.gameName }
    }

    syntax(gamemodeArg) {
        val gamemode = !gamemodeArg

        player.sendActionBar(Component.text("Joining ${gamemode!!.value.gameName}...", NamedTextColor.GREEN))

        /*player.showTitle(
            Title.title(
                Component.text("\uE00A"),
                Component.empty(),
                Title.Times.of(
                    Duration.ofMillis(500),
                    Duration.ofMillis(250),
                    Duration.ofMillis(500)
                )
            )
        )*/

        //Manager.scheduler.buildTask {
            player.joinGameOrNew(gamemode.value.gameName, GameManager.registeredGameMap[gamemode.key]!!.defaultGameOptions)
        //}.delay(Duration.ofMillis(500)).schedule()

    }

}, "play", "join")