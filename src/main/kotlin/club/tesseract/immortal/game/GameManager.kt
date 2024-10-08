package club.tesseract.immortal.game

import club.tesseract.immortal.Immortal
import club.tesseract.immortal.util.JedisStorage
import kotlinx.coroutines.runBlocking
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.tag.Tag
import net.minestom.server.tag.Taggable
import org.slf4j.LoggerFactory
import space.vectrix.flare.fastutil.Int2ObjectSyncMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier


object GameManager {

    private val LOGGER = LoggerFactory.getLogger(GameManager::class.java)

    // Instance Tags
    val doNotUnregisterTag = Tag.Boolean("doNotUnregister")
    val doNotAutoUnloadChunkTag = Tag.Boolean("doNotAutoUnloadChunk")
    val doNotUnloadChunksIndex = Tag.Long("doNotUnloadChunksIndex")
    val doNotUnloadChunksRadius = Tag.Integer("doNotUnloadChunksRadius")

    // Game Tags
    val gameNameTag = Tag.String("gameName")
    val gameIdTag = Tag.Integer("gameId")
    val joiningGameTag = Tag.Boolean("joiningGame")
    val playerSpectatingTag = Tag.Boolean("spectating")

    private val supplierMap: MutableMap<String, Supplier<Game>> = ConcurrentHashMap()

    private val gameIdMap: MutableMap<Int, Game> = Int2ObjectSyncMap.hashmap()
    private val gamesMap: MutableMap<String, MutableSet<Game>> = ConcurrentHashMap<String, MutableSet<Game>>()

    val Player.game: Game?
        get() = getPlayerGame(this)

    fun getRegisteredNames(): Set<String> = gamesMap.keys.toSet()

    fun getGameById(id: Int): Game? = gameIdMap[id]
    fun getGames(gameName: String): Set<Game>? = gamesMap[gameName]

    fun getPlayerGame(player: Player): Game? = player.instance?.let { getGameByTaggable(it) }
    fun getGameByTaggable(taggable: Taggable): Game? = gameIdMap[taggable.getTag(gameIdTag)]

    fun Player.joinGameOrNew(
        gameTypeName: String,
        hasCooldown: Boolean = false
    ): CompletableFuture<Game>? {
        if (hasCooldown && hasTag(joiningGameTag)) return null

        val gameFuture = findOrCreateGame(this, gameTypeName)

        return gameFuture.thenApply { game ->
            game.playerCount.incrementAndGet()

            val spawnPosition = game.getSpawnPosition(this)
            this.respawnPoint = spawnPosition
            this.setInstance(game.instance!!, spawnPosition)
            game
        }?.exceptionally {
            MinecraftServer.getExceptionManager().handleException(it)
            null
        }
    }

    fun findOrCreateGame(
        player: Player,
        gameTypeName: String
    ): CompletableFuture<Game> {
        val firstAvailableGame = getGames(gameTypeName)?.firstOrNull {
            it.canBeJoined(player)
        }

        if (firstAvailableGame != null) {
            val future = CompletableFuture<Game>()

            firstAvailableGame.create()?.thenRun {
                future.complete(firstAvailableGame)
            }

            return future
        }

        // No games are available, we need to create a new game
        return createGame(gameTypeName)

    }

    fun createGame(gameTypeName: String): CompletableFuture<Game> {

        val game = supplierMap[gameTypeName]?.get()
            ?: throw IllegalArgumentException("Game could not be created")

        gamesMap[gameTypeName]!!.add(game)
        gameIdMap[game.id] = game

        val createFuture = game.create() ?: throw NullPointerException("Game has been destroyed!")

        return createFuture
            .exceptionally {
                MinecraftServer.getExceptionManager().handleException(it)
                null
            }
            .thenApply { game }
    }

    fun removeGame(game: Game) {
        gameIdMap.remove(game.id)
        gamesMap[game.gameName]!!.remove(game)
    }

    fun registerGame(
        gameSupplier: Supplier<Game>,
        name: String,
    ) = runBlocking {
        supplierMap[name] = gameSupplier

        JedisStorage.jedis?.publish("registergame", "$name ${Immortal.gameConfig.serverName} ${Immortal.port} true")

        gamesMap[name] = mutableSetOf()

        LOGGER.info("Registered game type '${name}'")
    }
}