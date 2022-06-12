package dev.emortal.immortal.game

import dev.emortal.immortal.config.GameOptions
import dev.emortal.immortal.event.GameDestroyEvent
import dev.emortal.immortal.event.PlayerJoinGameEvent
import dev.emortal.immortal.event.PlayerLeaveGameEvent
import dev.emortal.immortal.game.GameManager.getNextGameId
import dev.emortal.immortal.game.GameManager.joinGameOrNew
import dev.emortal.immortal.util.MinestomRunnable
import dev.emortal.immortal.util.RedisStorage.redisson
import dev.emortal.immortal.util.reset
import dev.emortal.immortal.util.resetTeam
import dev.emortal.immortal.util.safeSetInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.title.Title
import net.minestom.server.adventure.audience.PacketGroupingAudience
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.EventDispatcher
import net.minestom.server.event.EventFilter
import net.minestom.server.event.EventNode
import net.minestom.server.instance.Instance
import net.minestom.server.scoreboard.Sidebar
import net.minestom.server.sound.SoundEvent
import net.minestom.server.timer.Schedulable
import net.minestom.server.timer.Scheduler
import org.tinylog.kotlin.Logger
import world.cepi.kstom.Manager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class Game(var gameOptions: GameOptions) : PacketGroupingAudience, Schedulable {

    private val playerCountTopic = redisson?.getTopic("playercount")

    val players: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    val spectators: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    val teams: MutableSet<Team> = ConcurrentHashMap.newKeySet()

    val playerLock = Object()

    val id = getNextGameId()

    var gameState = GameState.WAITING_FOR_PLAYERS

    val gameName = GameManager.registeredClassMap[this::class]!!
    val gameTypeInfo = GameManager.registeredGameMap[gameName] ?: throw Error("Game type not registered")

    open var spawnPosition = Pos(0.5, 70.0, 0.5)

    lateinit var instance: Instance

    val eventNode = EventNode.type("${gameTypeInfo.name}-$id", EventFilter.INSTANCE) { event, inst ->
        inst.uniqueId == instance.uniqueId
    }

    //val spectatorNode = EventNode.type("${gameTypeInfo.name}-$id-spectator", EventFilter.PLAYER) { event, plr ->
    //    players.contains(plr.uuid)
    //}

    val coroutineScope = CoroutineScope(Dispatchers.IO)
    val scheduler = Scheduler.newScheduler()

    var startingTask: MinestomRunnable? = null
    var scoreboard: Sidebar? = null

    private var destroyed = false
    private var created = AtomicBoolean(false)
    private var creating = AtomicBoolean(false)
    private val createLatch = CountDownLatch(1)

    suspend fun create(): Game {
        Logger.info("Creating game $gameName")
        if (created.get()) return this
        if (creating.get()) {
            Logger.warn("Create called while creating")
            createLatch.await(10, TimeUnit.SECONDS)
            return this
        }

        Logger.info("Creating game $gameName 23")

        creating.set(true)

        this.instance = instanceCreate()

        Manager.globalEvent.addChild(eventNode)
        if (gameTypeInfo.whenToRegisterEvents == WhenToRegisterEvents.IMMEDIATELY) registerEvents()

        createLatch.countDown()
        created.set(true)

        Logger.info("Created!! game $gameName")

        return this
    }

    internal suspend fun addPlayer(player: Player, joinMessage: Boolean = gameOptions.showsJoinLeaveMessages) {
        if (players.contains(player)) return

        Logger.info("${player.username} joining game '${gameTypeInfo.name}'")

        players.add(player)
        refreshPlayerCount()


        player.respawnPoint = spawnPosition

        if (joinMessage) sendMessage(
            Component.text()
                .append(Component.text("JOIN", NamedTextColor.GREEN, TextDecoration.BOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.username, NamedTextColor.GREEN))
                .append(Component.text(" joined the game ", NamedTextColor.GRAY))
                .also {
                    /*if (gameState == GameState.WAITING_FOR_PLAYERS)*/ it.append(Component.text("(${players.size}/${gameOptions.maxPlayers})", NamedTextColor.DARK_GRAY))
                }
        )

        player.safeSetInstance(instance, spawnPosition).get(10, TimeUnit.SECONDS)
        player.reset()
        player.resetTeam()

        scoreboard?.addViewer(player)


        playSound(Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 1.2f))
        player.playSound(Sound.sound(SoundEvent.ENTITY_ENDERMAN_TELEPORT, Sound.Source.MASTER, 1f, 1f))
        player.clearTitle()
        player.sendActionBar(Component.empty())

        //coroutineScope.launch {
            playerJoin(player)
        //}

        EventDispatcher.call(PlayerJoinGameEvent(this, player))

        if (gameState == GameState.WAITING_FOR_PLAYERS && players.size >= gameOptions.minPlayers) {
            if (startingTask == null) {
                startCountdown()
            }
        }
    }

    internal fun removePlayer(player: Player, leaveMessage: Boolean = gameOptions.showsJoinLeaveMessages) {
        if (!players.contains(player)) return

        Logger.info("${player.username} leaving game '${gameTypeInfo.name}'")

        teams.forEach { it.remove(player) }
        players.remove(player)
        scoreboard?.removeViewer(player)

        refreshPlayerCount()

        val leaveEvent = PlayerLeaveGameEvent(this, player)
        EventDispatcher.call(leaveEvent)

        if (leaveMessage) sendMessage(
            Component.text()
                .append(Component.text("QUIT", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text(" | ", NamedTextColor.DARK_GRAY))
                .append(Component.text(player.username, NamedTextColor.RED))
                .append(Component.text(" left the game ", NamedTextColor.GRAY))
                .also {
                    if (gameState == GameState.WAITING_FOR_PLAYERS) it.append(Component.text("(${players.size}/${gameOptions.maxPlayers})", NamedTextColor.DARK_GRAY))
                }
        )
        playSound(Sound.sound(SoundEvent.ENTITY_ITEM_PICKUP, Sound.Source.MASTER, 1f, 0.5f))

        if (players.size < gameOptions.minPlayers) {
            if (startingTask != null) {
                cancelCountdown()
            }
        }

        if (gameState == GameState.PLAYING) {
            val teamsWithPlayers = teams.filter { it.players.isNotEmpty() }
            if (teamsWithPlayers.size == 1) {
                victory(teamsWithPlayers.first())
                playerLeave(player)
                return
            }
            if (players.size == 1) {
                victory(players.first())
            }
        }

        if (players.size == 0) {
            destroy()
        }

        playerLeave(player)
    }

    private fun refreshPlayerCount() {
        playerCountTopic?.publishAsync("$gameName ${GameManager.gameMap[gameName]?.sumOf { it.players.size } ?: 0}")

        if (gameOptions.minPlayers > players.size && gameState == GameState.WAITING_FOR_PLAYERS) {
            scoreboard?.updateLineContent(
                "infoLine",
                Component.text(
                    "Waiting for players... (${gameOptions.minPlayers - players.size} more)",
                    NamedTextColor.GRAY
                )
            )
        }
    }

    internal suspend fun addSpectator(player: Player) {
        if (spectators.contains(player)) return
        if (players.contains(player)) return

        Logger.info("${player.username} started spectating game '${gameTypeInfo.name}'")

        spectators.add(player)

        player.respawnPoint = spawnPosition

        player.safeSetInstance(instance).get(20, TimeUnit.SECONDS)
        player.reset()

        scoreboard?.addViewer(player)

        player.isAutoViewable = false
        player.isInvisible = true
        player.gameMode = GameMode.SPECTATOR
        player.isAllowFlying = true
        player.isFlying = true
        //player.inventory.setItemStack(4, ItemStack.of(Material.COMPASS))
        player.playSound(Sound.sound(SoundEvent.ENTITY_BAT_AMBIENT, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

        coroutineScope.launch {
            spectatorJoin(player)
        }
    }

    internal fun removeSpectator(player: Player) {
        if (!spectators.contains(player)) return

        Logger.info("${player.username} stopped spectating game '${gameTypeInfo.name}'")

        spectators.remove(player)
        scoreboard?.removeViewer(player)

        spectatorLeave(player)
    }

    open fun spectatorJoin(player: Player) {}
    open fun spectatorLeave(player: Player) {}

    abstract fun playerJoin(player: Player)
    abstract fun playerLeave(player: Player)
    abstract fun gameStarted()
    abstract fun gameDestroyed()

    private fun startCountdown() {
        if (gameOptions.countdownSeconds == 0) {
            start()
            return
        }

        startingTask = object : MinestomRunnable(coroutineScope = coroutineScope, repeat = Duration.ofSeconds(1), iterations = gameOptions.countdownSeconds) {

            override suspend fun run() {
                val currentIter = currentIteration.get()

                scoreboard?.updateLineContent(
                    "infoLine",
                    Component.text()
                        .append(Component.text("Starting in ${gameOptions.countdownSeconds - currentIter} seconds", NamedTextColor.GREEN))
                        .build()
                )

                if ((gameOptions.countdownSeconds - currentIter) < 5 || currentIter % 5 == 0) {
                    playSound(Sound.sound(SoundEvent.BLOCK_NOTE_BLOCK_PLING, Sound.Source.AMBIENT, 1f, 1.2f))
                    showTitle(
                        Title.title(
                            Component.text(gameOptions.countdownSeconds - currentIter, NamedTextColor.GREEN, TextDecoration.BOLD),
                            Component.empty(),
                            Title.Times.times(
                                Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(250)
                            )
                        )
                    )
                }
            }

            override fun cancelled() {
                scheduler.scheduleNextTick {
                    start()
                }
            }

        }
    }

    fun cancelCountdown() {
        startingTask?.cancel()
        startingTask = null

        showTitle(
            Title.title(
                Component.empty(),
                Component.text("Start cancelled!", NamedTextColor.RED, TextDecoration.BOLD),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(1))
            )
        )
        playSound(Sound.sound(SoundEvent.ENTITY_VILLAGER_NO, Sound.Source.AMBIENT, 1f, 1f))
    }

    abstract fun registerEvents()

    fun start() {
        if (gameState == GameState.PLAYING) return

        playSound(Sound.sound(SoundEvent.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f), Sound.Emitter.self())

        startingTask = null
        gameState = GameState.PLAYING
        scoreboard?.updateLineContent("infoLine", Component.empty())

        if (gameTypeInfo.whenToRegisterEvents == WhenToRegisterEvents.GAME_START) registerEvents()
        gameStarted()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true

        Logger.info("A game of '${gameTypeInfo.name}' is ending")

        Manager.globalEvent.removeChild(eventNode)

        try {
            coroutineScope.cancel()
        } catch(ignored: Throwable) {
            Logger.warn("Coroutine scope cancelled without a Job, likely not an issue")
        }

        gameDestroyed()

        val destroyEvent = GameDestroyEvent(this)
        EventDispatcher.call(destroyEvent)

        GameManager.gameMap[gameName]?.remove(this)

        teams.forEach {
            it.destroy()
        }

        val debugMode = System.getProperty("debug").toBoolean()
        val debugGame = System.getProperty("debuggame")

        // Both spectators and players
        getPlayers().forEach {
            scoreboard?.removeViewer(it)

            coroutineScope.launch {
                if (debugMode) {
                    it.joinGameOrNew(debugGame)
                } else {
                    it.joinGameOrNew(gameName)
                }
            }

        }
        players.clear()
        spectators.clear()

        playerCountTopic?.publishAsync("$gameName ${GameManager.gameMap[gameName]?.sumOf { it.players.size } ?: 0}")
    }

    open fun canBeJoined(player: Player): Boolean {
        if (players.contains(player)) return false
        if (players.size >= gameOptions.maxPlayers) {
            return false
        }
        if (gameState == GameState.PLAYING) {
            return gameOptions.canJoinDuringGame
        }
        //if (gameOptions.private) {
        //    val party = gameCreator?.party ?: return false
        //
        //    return party.players.contains(player)
        //}
        return gameState.joinable
    }

    fun registerTeam(team: Team): Team {
        teams.add(team)
        return team
    }

    fun victory(team: Team) {
        victory(team.players)
    }
    fun victory(player: Player) {
        victory(listOf(player))
    }

    open fun victory(winningPlayers: Collection<Player>) {
        gameState = GameState.ENDING

        val victoryTitle = Title.title(
            Component.text("VICTORY!", NamedTextColor.GOLD, TextDecoration.BOLD),
            Component.text(EndGameQuotes.victory.random(), NamedTextColor.GRAY),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(3))
        )
        val defeatTitle = Title.title(
            Component.text("DEFEAT!", NamedTextColor.RED, TextDecoration.BOLD),
            Component.text(EndGameQuotes.defeat.random(), NamedTextColor.GRAY),
            Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(3))
        )

        val victorySound = Sound.sound(SoundEvent.ENTITY_VILLAGER_CELEBRATE, Sound.Source.MASTER, 1f, 1f)
        val victorySound2 = Sound.sound(SoundEvent.ENTITY_PLAYER_LEVELUP, Sound.Source.MASTER, 1f, 1f)

        val defeatSound = Sound.sound(SoundEvent.ENTITY_VILLAGER_DEATH, Sound.Source.MASTER, 1f, 0.8f)

        players.forEach {
            if (winningPlayers.contains(it)) {
                it.showTitle(victoryTitle)
                it.playSound(victorySound)
                it.playSound(victorySound2)
            } else {
                it.showTitle(defeatTitle)
                it.playSound(defeatSound)
            }
        }

        gameWon(winningPlayers)

        Manager.scheduler.buildTask { destroy() }.delay(Duration.ofSeconds(6)).schedule()
    }

    open fun gameWon(winningPlayers: Collection<Player>) {}

    abstract suspend fun instanceCreate(): Instance

    override fun getPlayers(): MutableCollection<Player> = (players + spectators).toMutableSet()

    override fun scheduler(): Scheduler = scheduler

    override fun equals(other: Any?): Boolean {
        if (other !is Game) return false
        return other.id == this.id
    }

}
