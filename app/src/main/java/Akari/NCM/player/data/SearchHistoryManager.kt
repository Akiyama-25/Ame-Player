package Akari.NCM.player.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 搜索历史记录管理，支持持久化存储与增删清空。
 */
class SearchHistoryManager(context: Context) {
    private val prefs = context.getSharedPreferences("ame_search_prefs", Context.MODE_PRIVATE)
    private val key = "search_history_json"

    private val _history = MutableStateFlow<List<String>>(loadHistory())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    private fun loadHistory(): List<String> {
        val jsonStr = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            Json.decodeFromString<List<String>>(jsonStr)
        }.getOrDefault(emptyList())
    }

    fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = _history.value.toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val limited = current.take(20)
        _history.value = limited
        prefs.edit().putString(key, Json.encodeToString(limited)).apply()
    }

    fun removeSearchQuery(query: String) {
        val current = _history.value.toMutableList()
        if (current.remove(query)) {
            _history.value = current
            prefs.edit().putString(key, Json.encodeToString(current)).apply()
        }
    }

    fun clearHistory() {
        _history.value = emptyList()
        prefs.edit().remove(key).apply()
    }
}
