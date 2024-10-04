package club.tesseract.immortal.util

import club.tesseract.immortal.Immortal
import club.tesseract.immortal.game.GameManager
import club.tesseract.immortal.game.GameManager.game
import club.tesseract.immortal.game.GameManager.joinGameOrNew
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import org.slf4j.LoggerFactory
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.JedisPubSub
import java.util.*


object JedisStorage {

    private val LOGGER = LoggerFactory.getLogger(JedisStorage::class.java)

    val jedis =
        if (Immortal.redisAddress.isNotBlank()) {
            JedisPooled(Immortal.gameConfig.redisAddress)
        } else null

    fun init() {
        if (jedis == null) return

        val listenerScope = CoroutineScope(Dispatchers.IO)

        listenerScope.launch {
            // Create proxyhello listener
            val proxyHelloSub = object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    GameManager.getRegisteredNames().forEach {
                        LOGGER.info("Received proxyhello, re-registering game $it")
                        jedis.publish(
                            "registergame",
                            "$it ${Immortal.gameConfig.serverName} ${Immortal.port}"
                        )
                    }
                }
            }
            jedis.subscribe(proxyHelloSub, "proxyhello")
        }

        listenerScope.launch {
            val cm = MinecraftServer.getConnectionManager()

            val playerPubSub = object : JedisPubSub() {
                override fun onMessage(channel: String, message: String) {
                    val args = message.split(" ")

                    when (args[0].lowercase()) {
                        "changegame" -> {
                            LOGGER.warn(message)

                            val player = cm.getOnlinePlayerByUuid((UUID.fromString(args[1]))) ?: return
                            val playerGame = player.game
                            val subgame = args[2]
                            if (!GameManager.getRegisteredNames().contains(subgame)) {
                                // Invalid subgame, ignore message
                                LOGGER.warn("Invalid subgame $subgame")
                                return
                            }

                            if (playerGame == null) {
                                LOGGER.warn("Player game is null")
                                return
                            }

                            if (playerGame.gameName == subgame) {
                                LOGGER.warn("Player subgame is already the same")
                                return
                            }

                            playerGame.scoreboard?.removeViewer(player) // fixes bugginess

                            player.scheduleNextTick {
                                player.joinGameOrNew(subgame, hasCooldown = false)
                            }
                        }

                        "spectateplayer" -> {
                            val player = cm.getOnlinePlayerByUuid((UUID.fromString(args[1]))) ?: return
                            val playerToSpectate = cm.getOnlinePlayerByUuid((UUID.fromString(args[2]))) ?: return

                            val prevGame = player.game
                            val game = playerToSpectate.game
                            if (game != null) {
                                if (!game.allowsSpectators) {
                                    player.sendMessage(Component.text("That game does not allow spectating", NamedTextColor.RED))
                                    return
                                }
                                if (game.id == player.game?.id) {
                                    player.sendMessage(Component.text("That player is not on a game", NamedTextColor.RED))
                                    return
                                }

                                val spawnPosition = game.getSpawnPosition(player, spectator = true)

                                prevGame?.scoreboard?.removeViewer(player) // fixes bugginess

                                player.setInstance(game.instance!!, spawnPosition).thenRun {
                                    player.setTag(GameManager.playerSpectatingTag, true)
                                }
                            }
                        }
                    }
                }
            }
            jedis.subscribe(playerPubSub, "playerpubsub${Immortal.gameConfig.serverName}")
        }

    }

}