package Akari.NCM.player.core

import kotlinx.serialization.Serializable

@Serializable
data class HomepageDragonBall(
    val id: Long = 0L,
    val name: String = "",
    val iconUrl: String = "",
    val url: String = ""
)

@Serializable
data class HomepageBlock(
    val blockCode: String = "",
    val title: String = "",
    val creatives: List<Creative> = emptyList()
) {
    @Serializable
    data class Creative(
        val creativeType: String = "",
        val title: String = "",
        val imageUrl: String = "",
        val resourceId: String = "",
        val playCount: Long = 0L
    )
}
