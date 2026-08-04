package com.deepseek.coder.data.skill

import com.deepseek.coder.core.AppLogger
import com.deepseek.coder.domain.skill.ToolSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具执行结果（SPEC-Skill-v1.2 §4.3）。
 */
sealed class ToolResult {
    data class Success(val content: String) : ToolResult()
    data class Failure(val error: String) : ToolResult()
}

/**
 * 单个工具的实现接口。
 */
interface ToolImpl {
    val name: String
    suspend fun execute(args: JsonObject): ToolResult
}

/**
 * 工具执行器（SPEC-Skill-v1.2 §3.3 / §3.4）。
 *
 * 职责：
 *  1. 入口统一参数校验（v1.2 决策 10：按 ToolSpec.parameters JSON Schema，校验 type/required/enum）
 *  2. 会话级 LRU 缓存（v1.2 决策 13：容量 16，按工具名+args 哈希）
 *  3. 路由到具体 [ToolImpl]
 *
 * 失败时返回 [ToolResult.Failure]，content 写错误信息让模型自行决策。
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val impls: Set<@JvmSuppressWildcards ToolImpl>
) {

    private val byName: Map<String, ToolImpl> = impls.associateBy { it.name }
    private val cache = ToolResultCache()
    private val mutex = Mutex()

    /**
     * 执行工具（含参数校验 + 缓存）。
     *
     * @param spec 工具声明（含参数 JSON Schema，用于校验）
     * @param args 模型传入的参数
     */
    suspend fun execute(spec: ToolSpec, args: JsonObject): ToolResult {
        // 1. 参数校验
        val validateError = validateArgs(spec.parameters, args)
        if (validateError != null) {
            AppLogger.w(null, "ToolExecutor: param validation failed for %s: %s", spec.name, validateError)
            return ToolResult.Failure("参数校验失败：$validateError")
        }

        // 2. 缓存查询
        val key = cacheKey(spec.name, args)
        mutex.withLock {
            cache.get(key)?.let {
                AppLogger.d("ToolExecutor: [cache hit] %s", spec.name)
                return it
            }
        }

        // 3. 路由执行
        val impl = byName[spec.name]
            ?: return ToolResult.Failure("未知工具：${spec.name}")

        val result = runCatching { impl.execute(args) }
            .getOrElse {
                AppLogger.w(it, "ToolExecutor: %s execute failed", spec.name)
                ToolResult.Failure("工具执行异常：${it.message ?: it::class.simpleName}")
            }

        // 4. 写缓存（Failure 不缓存，让模型重试有机会成功）
        if (result is ToolResult.Success) {
            mutex.withLock { cache.put(key, result) }
        }
        return result
    }

    /** 清理会话级缓存（会话结束时调用）。 */
    fun clearCache() = cache.clear()

    // ------------------------------------------------------------------
    // 轻量 JSON Schema 校验（type / required / enum）
    // ------------------------------------------------------------------

    private fun validateArgs(schema: JsonObject, args: JsonObject): String? {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull
        if (type == "object") {
            val props = schema["properties"]?.jsonObject
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            // required 字段存在性
            for (r in required) {
                if (r !in args) return "缺少必填字段：$r"
            }
            // 各字段类型校验
            if (props != null) {
                for ((k, v) in args) {
                    val propSchema = props[k] ?: continue  // 未知字段放行
                    val err = validateField(k, v, propSchema.jsonObject)
                    if (err != null) return err
                }
            }
        }
        return null
    }

    private fun validateField(name: String, value: JsonElement, schema: JsonObject): String? {
        val type = schema["type"]?.jsonPrimitive?.contentOrNull ?: return null
        val ok = when (type) {
            "string" -> value is JsonPrimitive && value.isString
            "integer" -> value is JsonPrimitive && value.intOrNull != null
            "number" -> value is JsonPrimitive && value.doubleOrNull != null
            "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
            "array" -> value is JsonArray
            "object" -> value is JsonObject
            else -> true
        }
        if (!ok) return "字段 $name 类型应为 $type"
        // enum 校验
        schema["enum"]?.jsonArray?.map { it.jsonPrimitive.content }?.let { allowed ->
            val v = (value as? JsonPrimitive)?.contentOrNull
            if (v != null && v !in allowed) return "字段 $name 值 $v 不在允许范围 $allowed"
        }
        return null
    }

    private fun cacheKey(name: String, args: JsonObject): String {
        return "$name:${args.toString()}"
    }
}

/**
 * 会话级 LRU 缓存（容量 16，SPEC-Skill-v1.2 §3.4）。
 */
internal class ToolResultCache(private val maxCapacity: Int = 16) {
    private val map = object : LinkedHashMap<String, ToolResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ToolResult>?): Boolean {
            return size > maxCapacity
        }
    }

    @Synchronized fun get(key: String): ToolResult? = map[key]
    @Synchronized fun put(key: String, value: ToolResult) { map[key] = value }
    @Synchronized fun clear() { map.clear() }
}
