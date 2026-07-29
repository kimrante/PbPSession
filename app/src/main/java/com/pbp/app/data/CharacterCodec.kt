package com.pbp.app.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * ccfolia식 캐릭터 코드(JSON) → 프로필 변환.
 *
 * `{"kind":"character","data":{...}}` 형태에서 commands를 제외한 전부를
 * value 목록으로 옮긴다:
 *   name → 캐릭터 이름
 *   status[] → label=value, max가 있으면 "{label}max"=max (예: HP=14, HPmax=13)
 *   params[] → label=value
 *   initiative, memo → 같은 이름의 value
 */
object CharacterCodec {

    data class Imported(val name: String, val stats: List<Pair<String, String>>)

    fun parse(text: String): Imported? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // 스프레드시트 등에서 복사하면 따옴표가 ""로 겹쳐 있을 수 있다
        return parseJson(trimmed)
            ?: if ("\"\"" in trimmed) parseJson(trimmed.replace("\"\"", "\"")) else null
    }

    private fun parseJson(text: String): Imported? = runCatching {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = JsonParser.parseString(text.substring(start, end + 1)).asJsonObject
        if (root.get("kind")?.asString != "character") return null
        val data = root.getAsJsonObject("data") ?: return null
        val name = data.get("name")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val stats = mutableListOf<Pair<String, String>>()
        data.get("initiative")?.takeIf { it.isJsonPrimitive }?.let {
            stats += "initiative" to it.asString
        }
        data.get("status")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val label = o.get("label")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            o.get("value")?.takeIf { it.isJsonPrimitive }?.let { stats += label to it.asString }
            o.get("max")?.takeIf { it.isJsonPrimitive }?.let { stats += "${label}max" to it.asString }
        }
        data.get("params")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val label = o.get("label")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            o.get("value")?.takeIf { it.isJsonPrimitive }?.let { stats += label to it.asString }
        }
        data.get("memo")?.asString?.trim()?.takeIf { it.isNotEmpty() }?.let {
            stats += "memo" to it
        }

        // 같은 이름이 겹치면 먼저 나온 값을 유지
        val seen = mutableSetOf<String>()
        Imported(name, stats.filter { seen.add(it.first) })
    }.getOrNull()
}
