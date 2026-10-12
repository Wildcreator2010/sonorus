package com.wildcreator.sonorus.domain.agent.tools

import com.wildcreator.sonorus.domain.agent.AgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** 安全的数学表达式计算（递归下降解析，无 eval） */
class CalculatorTool : AgentTool {

    override val name = "calculator"
    override val description = "计算数学表达式，支持 + - * / % ^、括号、函数 sqrt/sin/cos/tan/log/ln/abs/floor/ceil 与常量 pi/e"
    override val parameters: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("expression") {
                put("type", "string")
                put("description", "要计算的数学表达式，例如 (3+4)*2^5")
            }
        }
        put("required", buildJsonArray { add(JsonPrimitive("expression")) })
    }

    override suspend fun execute(argumentsJson: String): String {
        val expr = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject["expression"]?.jsonPrimitive?.content
        }.getOrNull() ?: return "错误：缺少 expression 参数"

        return try {
            val value = Parser(expr).parse()
            if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                value.toString()
            }
        } catch (e: Exception) {
            "计算失败：${e.message}"
        }
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = parseExpr()
            skipWs()
            require(pos == s.length) { "表达式在第 ${pos + 1} 个字符处无法解析" }
            return v
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun parseExpr(): Double {
            var v = parseTerm()
            while (true) {
                skipWs()
                v = when {
                    match('+') -> v + parseTerm()
                    match('-') -> v - parseTerm()
                    else -> return v
                }
            }
        }

        private fun parseTerm(): Double {
            var v = parseFactor()
            while (true) {
                skipWs()
                v = when {
                    match('*') -> v * parseFactor()
                    match('/') -> v / parseFactor()
                    match('%') -> v % parseFactor()
                    else -> return v
                }
            }
        }

        private fun parseFactor(): Double {
            skipWs()
            val base = parseUnary()
            skipWs()
            return if (match('^')) base.pow(parseFactor()) else base // 右结合
        }

        private fun parseUnary(): Double {
            skipWs()
            return if (match('-')) -parseUnary() else parsePrimary()
        }

        private fun parsePrimary(): Double {
            skipWs()
            if (match('(')) {
                val v = parseExpr()
                skipWs()
                require(match(')')) { "缺少右括号" }
                return v
            }
            if (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                val start = pos
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                return s.substring(start, pos).toDouble()
            }
            if (pos < s.length && s[pos].isLetter()) {
                val start = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val name = s.substring(start, pos)
                skipWs()
                if (match('(')) {
                    val arg = parseExpr()
                    skipWs()
                    require(match(')')) { "缺少右括号" }
                    return when (name) {
                        "sqrt" -> sqrt(arg)
                        "sin" -> sin(arg)
                        "cos" -> cos(arg)
                        "tan" -> tan(arg)
                        "log" -> log10(arg)
                        "ln" -> ln(arg)
                        "abs" -> abs(arg)
                        "floor" -> floor(arg)
                        "ceil" -> ceil(arg)
                        else -> throw IllegalArgumentException("未知函数 $name")
                    }
                }
                return when (name) {
                    "pi" -> Math.PI
                    "e" -> Math.E
                    else -> throw IllegalArgumentException("未知常量 $name")
                }
            }
            throw IllegalArgumentException("第 ${pos + 1} 个字符处语法错误")
        }

        private fun match(c: Char): Boolean {
            return if (pos < s.length && s[pos] == c) {
                pos++
                true
            } else {
                false
            }
        }
    }
}
