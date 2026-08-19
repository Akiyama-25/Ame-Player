package Akari.NCM.player.core

enum class HomepageMode(val value: String) {
    APP_LIKE("app_like"),
    DAILY_ONLY("daily_only"),
    HIGH_QUALITY_ONLY("high_quality_only");

    companion object {
        fun fromValue(value: String): HomepageMode {
            return entries.find { it.value == value } ?: APP_LIKE
        }
    }
}
