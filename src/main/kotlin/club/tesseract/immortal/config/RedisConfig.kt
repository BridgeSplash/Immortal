package club.tesseract.immortal.config

import kotlinx.serialization.Serializable


@kotlinx.serialization.Serializable
data class RedisConfig(
    val active: Boolean = false,
    val host: String = "localhost",
    val port: Int = 6379,
    val username: String? = null,
    val password: String? = null
)
