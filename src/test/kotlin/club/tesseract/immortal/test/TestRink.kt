package club.tesseract.immortal.test

import club.tesseract.immortal.MinestomServer
import net.minestom.server.MinecraftServer
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test


object TestRink {

    @Test
    fun runServer(): Unit {
        MinestomServer.Builder()
            .commonVariables()
            .mojangAuth(false)
            .address("0.0.0.0")
            .port(25565)
            .buildAndStart()

        Assertions.assertTrue(MinecraftServer.isStarted())
        Assertions.assertFalse(MinecraftServer.isStopping())
    }

}