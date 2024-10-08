package club.tesseract.immortal.commands

import club.tesseract.immortal.game.GameManager.game
import club.tesseract.immortal.game.GameState
import club.tesseract.immortal.permission.PermissionUtils.hasPermission
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.Command
import net.minestom.server.entity.Player

internal object ForceStartCommand : Command("forcestart") {

    init {
        setCondition { sender, _ ->
            sender.hasPermission("immortal.forcestart")
        }

        setDefaultExecutor { sender, _ ->
            if (!sender.hasPermission("immortal.forcestart")) {
                sender.sendMessage("No permission")
                return@setDefaultExecutor
            }

            val player = sender as? Player ?: return@setDefaultExecutor
            val playerGame = player.game

            if (playerGame == null) {
                player.sendMessage(Component.text("You are not in a game", NamedTextColor.RED))
                return@setDefaultExecutor
            }
            if (playerGame.gameState != GameState.WAITING_FOR_PLAYERS) {
                player.sendMessage(Component.text("The game has already started", NamedTextColor.RED))
                return@setDefaultExecutor
            }

            playerGame.startingTask?.cancel()
            playerGame.start()
            playerGame.sendMessage(Component.text("${player.username} started the game early", NamedTextColor.GOLD))
        }
    }

}