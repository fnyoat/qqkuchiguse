/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.github.fnyoat.qqkuchiguse.util

/**
 * 微型递归下降数学表达式求值器。
 * 支持：+ - * / 括号 一元 +/- 后缀 %（如 50% => 0.5）
 */
object MathExprEvaluator {

    fun evaluate(expression: String): Double {
        val tokens = tokenize(expression)
        val parser = Parser(tokens)
        val result = parser.parseExpression()
        if (parser.hasMore) throw IllegalArgumentException("Unexpected token at position ${parser.position}")
        if (result.isNaN() || result.isInfinite()) throw IllegalArgumentException("Expression result is not finite")
        return result
    }

    private sealed interface Token
    private data class NumberToken(val value: Double) : Token
    private enum class Op(val symbol: Char) {
        ADD('+'), SUB('-'), MUL('*'), DIV('/'), PERCENT('%'), L_PAREN('('), R_PAREN(')');
        companion object { fun from(symbol: Char): Op? = entries.firstOrNull { it.symbol == symbol } }
    }
    private class OpToken(val op: Op) : Token

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0
        private fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null
        private fun peekOp(): Op? = (peek() as? OpToken)?.op
        val hasMore: Boolean get() = pos < tokens.size
        val position: Int get() = pos

        fun parseExpression(): Double = parseAddSub()

        private fun parseAddSub(): Double {
            var value = parseMulDiv()
            while (true) {
                when (peekOp()) {
                    Op.ADD -> { pos++; value += parseMulDiv() }
                    Op.SUB -> { pos++; value -= parseMulDiv() }
                    else -> return value
                }
            }
        }

        private fun parseMulDiv(): Double {
            var value = parseUnary()
            while (true) {
                when (peekOp()) {
                    Op.MUL -> { pos++; value *= parseUnary() }
                    Op.DIV -> { pos++; value /= parseUnary() }
                    else -> return value
                }
            }
        }

        private fun parseUnary(): Double = when (peekOp()) {
            Op.SUB -> { pos++; -parseUnary() }
            Op.ADD -> { pos++; parseUnary() }
            else -> parsePostfix()
        }

        private fun parsePostfix(): Double {
            var value = parsePrimary()
            while (peekOp() == Op.PERCENT) { pos++; value /= 100.0 }
            return value
        }

        private fun parsePrimary(): Double {
            val tok = peek() ?: throw IllegalArgumentException("Unexpected end of expression")
            return when (tok) {
                is NumberToken -> { pos++; tok.value }
                is OpToken -> if (tok.op == Op.L_PAREN) {
                    pos++; val result = parseAddSub()
                    if (peekOp() != Op.R_PAREN) throw IllegalArgumentException("Missing closing parenthesis")
                    pos++; result
                } else throw IllegalArgumentException("Unexpected token '${tok.op.symbol}'")
            }
        }
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = ArrayList<Token>()
        var i = 0; val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    val sb = StringBuilder(); var dot = false
                    while (i < n) {
                        val ch = input[i]
                        if (ch.isDigit()) { sb.append(ch); i++ }
                        else if (ch == '.' && !dot) { dot = true; sb.append(ch); i++ }
                        else break
                    }
                    if (sb.isEmpty() || sb.toString() == ".") throw IllegalArgumentException("Invalid number")
                    tokens.add(NumberToken(sb.toString().toDouble()))
                }
                else -> { val op = Op.from(c) ?: throw IllegalArgumentException("Unexpected character '$c'"); tokens.add(OpToken(op)); i++ }
            }
        }
        return tokens
    }
}