package Akari.NCM.player.core

import kotlinx.serialization.Serializable

@Serializable
data class QrKeyResponse(
    val code: Int = 0,
    val data: QrKeyData? = null
) {
    @Serializable
    data class QrKeyData(val unikey: String = "")
}

@Serializable
data class QrCreateResponse(
    val code: Int = 0,
    val data: QrCreateData? = null
) {
    @Serializable
    data class QrCreateData(
        val qrurl: String = "",
        val qrimg: String = ""
    )
}

@Serializable
data class QrCheckResponse(
    val code: Int = 0,
    val message: String = "",
    val cookie: String = ""
)

@Serializable
data class UserProfile(
    val userId: Long = 0L,
    val nickname: String = "",
    val avatarUrl: String = "",
    val vipType: Int = 0,
    val signature: String = ""
)

@Serializable
data class NcmUserPlaylist(
    val id: Long = 0L,
    val name: String = "",
    val coverImgUrl: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0L,
    val creatorName: String = "",
    val description: String = ""
)

@Serializable
data class QrCheckResult(
    val status: QrStatus,
    val message: String = "",
    val cookie: String = ""
)

enum class QrStatus {
    EXPIRED,      // 800
    WAITING,      // 801
    SCANNING,     // 802
    SUCCESS,      // 803
    UNKNOWN
}
