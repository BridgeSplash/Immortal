package club.tesseract.immortal.util

import club.tesseract.immortal.permission.PermissionUtils
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.sound.Sound
import net.kyori.adventure.sound.SoundStop
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.attribute.Attribute
import net.minestom.server.network.packet.server.play.TeamsPacket
import net.minestom.server.utils.NamespaceID

fun Player.reset() {
    entityMeta.setNotifyAboutChanges(false)

    inventory.clear()
    isAutoViewable = true
    isInvisible = false
    isGlowing = false
    isSneaking = false
    isAllowFlying = false
    isFlying = false
    additionalHearts = 0f
    gameMode = GameMode.ADVENTURE
    food = 20
    level = 0
    vehicle?.removePassenger(this)
    arrowCount = 0
    fireTicks = 0
    getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).baseValue = 0.1
    setCanPickupItem(true)
    if (openInventory != null) closeInventory()
    setNoGravity(false)
    heal()
    clearEffects()
    stopSpectating()

    stopSound(SoundStop.all())

    MinecraftServer.getBossBarManager().getPlayerBossBars(this).forEach {
        MinecraftServer.getBossBarManager().removeBossBar(this, it)
    }

    entityMeta.setNotifyAboutChanges(true)

    updateViewableRule { true }
    updateViewerRule { true }
//    askSynchronization()
}

fun Player.resetTeam() {
    PermissionUtils.refreshPrefix(this)

    team = MinecraftServer.getTeamManager().createBuilder(username + "default")
        .collisionRule(TeamsPacket.CollisionRule.NEVER)
        .build()
}

fun Player.sendServer(gameName: String) {
    JedisStorage.jedis?.publish("joingame", "$gameName ${this.uuid}")
}

fun Audience.playSound(sound: Sound, position: Point) =
    playSound(sound, position.x(), position.y(), position.z())