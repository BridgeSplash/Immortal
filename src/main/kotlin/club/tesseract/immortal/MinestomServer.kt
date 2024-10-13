package club.tesseract.immortal

import io.shulkermc.serveragent.minestom.ShulkerServerAgentMinestom
import net.hollowcube.minestom.extensions.ExtensionBootstrap
import net.minestom.server.MinecraftServer
import net.minestom.server.extras.MojangAuth
import net.minestom.server.world.Difficulty
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.math.RoundingMode
import java.text.DecimalFormat


class MinestomServer(builder: Builder) {


    companion object{
        private var LOGGER: Logger

        private val DEFAULT_ADDRESS = "0.0.0.0"
        private val DEFAULT_PORT = "25565"

        val development get() = System.getenv("SHULKER_SERVER_NAMESPACE") == null

        init{
            val loggerConfigFile = if(isProduction())  "logback-prod.xml" else "logback-dev.xml"
            System.setProperty("logback.configurationFile", loggerConfigFile);

            LOGGER = LoggerFactory.getLogger(MinestomServer::class.java)
        }

        fun isProduction(): Boolean{
            return !development
        }
    }

    fun builder(): Builder {
        return Builder()
    }

    private val address: String
    private val port: Int
    private val startTime: Long = System.currentTimeMillis()

    private var server: ExtensionBootstrap

    init {
        address = builder.address
        port = builder.port

        if(isProduction()) {
            ShulkerServerAgentMinestom.init(java.util.logging.Logger.getLogger("Shulker"))
        }
        server = ExtensionBootstrap.init()

        MinecraftServer.setBrandName("BridgeSplash")
        MinecraftServer.setDifficulty(Difficulty.PEACEFUL)

        val compression = if(development) 0 else 256
        MinecraftServer.setCompressionThreshold(compression)

        if (builder.mojangAuth && development) {
            LOGGER.info("Enabling Mojang authentication")
            MojangAuth.init()
        }

        LOGGER.info("Starting server at {}:{}", builder.address, builder.port)
    }

    fun start() {
        server.start(address, port)

        Immortal.init()

        // Log the done time
        val df = DecimalFormat("#.##")
        df.roundingMode = RoundingMode.CEILING
        LOGGER.info("Done (${df.format((System.currentTimeMillis().toDouble() - startTime.toDouble()).div(1000))}s)! ")
    }



    class Builder {
        init {
            // we do this because env variables in dockerfiles break k8s env variables?
            // So we can't add system properties in the dockerfile, but we can add them at runtime
            for ((key, value) in System.getenv()) {
                if (System.getProperty(key) == null) {
                    System.setProperty(key, value)
                }
            }
        }

        internal var address = getValue("minestom.address", DEFAULT_ADDRESS)
        internal var port = getValue("VELOCITY_SERVICE_PORT_MINECRAFT", DEFAULT_PORT).toInt()
        internal var mojangAuth = isProduction()

        fun address(address: String): Builder {
            this.address = address
            return this
        }

        fun port(port: Int): Builder {
            this.port = port
            return this
        }

        fun mojangAuth(mojangAuth: Boolean): Builder {
            this.mojangAuth = mojangAuth
            return this
        }


        fun commonVariables() = apply{
            System.getProperties().setProperty("minestom.chunk-view-distance", "8")
            System.getProperties().setProperty("minestom.entity-view-distance", "5")
            System.getProperties().setProperty("minestom.use-new-chunk-sending", "true")
        }

        fun build(): MinestomServer {
            return MinestomServer(this)
        }

        fun buildAndStart() {
            this.build().start()
        }

        companion object {
            private fun getValue(key: String, defaultValue: String): String {
                var value = System.getProperty(key)
                if (value != null && value.isNotEmpty()) return value
                value = System.getenv(key)
                return if (value != null && value.isNotEmpty()) value else defaultValue
            }
        }
    }

}