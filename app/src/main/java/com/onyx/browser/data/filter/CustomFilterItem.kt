package com.onyx.browser.data.filter

import org.json.JSONObject

data class CustomFilterItem(
    val id: String,
    val name: String,
    val url: String = "",
    val rules: String = "",
    val isUrlType: Boolean = true,
    var isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("url", url)
        put("rules", rules)
        put("isUrlType", isUrlType)
        put("isEnabled", isEnabled)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): CustomFilterItem = CustomFilterItem(
            id = json.getString("id"),
            name = json.getString("name"),
            url = json.optString("url", ""),
            rules = json.optString("rules", ""),
            isUrlType = json.optBoolean("isUrlType", true),
            isEnabled = json.optBoolean("isEnabled", true),
            createdAt = json.optLong("createdAt", System.currentTimeMillis())
        )
    }
}
