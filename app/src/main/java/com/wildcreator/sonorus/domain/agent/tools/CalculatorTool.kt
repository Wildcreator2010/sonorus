package com.wildcreator.sonorus.domain.agent.tools

import com.wildcreator.sonorus.domain.agent.AgentTool
import com.wildcreator.sonorus.domain.agent.ToolResult
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

    override suspend fun execute(argumentsJson: String): ToolResult {
        val expr = runCatching {
            Json.parseToJsonElement(argumentsJson).jsonObject["expression"]?.jsonPrimitive?.content
        }.getOrNull() ?: return ToolResult(false, "错误：缺少 expression 参数")

        return try {
            val value = Parser(expr).parse()
            val text = if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                value.toString()
            }
            ToolResult(true, text)
        } catch (e: Exception) {
            ToolResult(false, "计算失败：${e.message}")
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
            var v = parseUnary()
            while (true) {
                skipWs()
                v = when {
                    match('*') -> v * parseUnary()
                    match('/') -> v / parseUnary()
                    match('%') -> v % parseUnary()
                    else -> return v
                }
            }
        }

        /**
         * 负号优先级低于乘方：-2^2 = -(2^2)，不是 (-2)^2。
         * 乘方右结合，并允许指数带负号（2^-3）。
         */
        private fun parseUnary(): Double {
            skipWs()
            return if (match('-')) -parseUnary() else parsePower()
        }

        private fun parsePower(): Double {
            val base = parsePrimary()
            skipWs()
            return if (match('^')) base.pow(parseUnary()) else base
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
                return parseNumber()
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

        /**
         * 数字字面量：整数/小数 + 可选科学计数法。
         * 只允许一个小数点、指数必须有数字，避免 "1.2.3"、"1e" 被静默截断。
         */
        private fun parseNumber(): Double {
            val start = pos
            var digits = 0
            while (pos < s.length && s[pos].isDigit()) {
                pos++
                digits++
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                while (pos < s.length && s[pos].isDigit()) {
                    pos++
                    digits++
                }
            }
            if (digits == 0) throw IllegalArgumentException("第 ${start + 1} 个字符处缺少数字")

            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                val exponentStart = pos
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                val exponentDigits = pos
                while (pos < s.length && s[pos].isDigit()) pos++
                if (pos == exponentDigits) {
                    pos = exponentStart
                    throw IllegalArgumentException("科学计数法缺少指数")
                }
            }
            return s.substring(start, pos).toDouble()
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
