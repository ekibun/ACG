package soko.ekibun.quickjs

import soko.ekibun.loadLibrary

object Highlight {
  init {
    loadLibrary("quickjs")
  }

  private fun parseString(
    input: TokenParser,
    sep: Char,
  ): Int {
    while (input.offset < input.s.length) {
      val c = input.s[input.offset]
      input.offset++
      when (c) {
        sep -> return 0
        '$' ->
          if (sep == '`' && input.offset < input.s.length && input.s[input.offset] == '{') {
            input.offset++
            return 1
          }
        '\\' -> input.offset++
        '\n' -> if (sep != '`') return -2 // 出错
      }
    }
    return -1
  }

  enum class Token {
    STRING,
    COMMENT,
    REGEXP,
    IDENT,
  }

  /** 标识符首字符（字母/下划线/$）。 */
  private external fun isIdentFirst(c: Char): Boolean

  /** 标识符后续字符（首字符集 + 数字）。 */
  private external fun isIdentNext(c: Char): Boolean

  private data class TokenParser(
    val s: String,
    var offset: Int = 0,
  )

  private fun parseCurry(
    input: TokenParser,
    endOnCurry: Boolean,
    cb: (Token, Int, Int) -> Unit,
  ) {
    while (input.offset < input.s.length) {
      val c = input.s[input.offset]
      input.offset++
      when (c) {
        '`' -> {
          while (true) {
            val st = input.offset - 1
            val ret = parseString(input, '`')
            if (ret == -1) return
            cb(Token.STRING, st, input.offset)
            if (ret == 0) break
            parseCurry(input, true, cb)
          }
        }
        '\'', '"' -> {
          val st = input.offset - 1
          parseString(input, c)
          cb(Token.STRING, st, input.offset)
        }
        '/' -> {
          if (input.offset < input.s.length) {
            when (input.s[input.offset]) {
              '/' -> {
                val st = input.offset - 1
                parseString(input, '\n')
                cb(Token.COMMENT, st, input.offset)
              }
              '*' -> {
                var ret = input.s.indexOf("*/", input.offset + 1)
                if (ret == -1) ret = input.s.length - 2
                cb(Token.COMMENT, input.offset - 1, ret + 2)
                input.offset = ret + 2
              }
              else -> {
                val st = input.offset - 1
                val ret = parseString(input, '/')
                val offset = input.offset
                val flag = "gimsuy".toMutableList()
                if (ret == 0) {
                  while (input.offset < input.s.length) {
                    val cc = flag.indexOf(input.s[input.offset])
                    if (cc < 0) break
                    flag.removeAt(cc)
                    input.offset++
                  }
                }
                if (input.offset < input.s.length && isIdentNext(input.s[input.offset])) {
                  input.offset = offset
                }
                cb(Token.REGEXP, st, input.offset)
              }
            }
          }
        }
        '}' -> if (endOnCurry) return
        else ->
          if (isIdentFirst(c)) {
            val st = input.offset - 1
            while (input.offset < input.s.length && isIdentNext(input.s[input.offset])) {
              input.offset++
            }
            when (input.s.substring(st, input.offset)) {
              "null", "false", "true",
              "if", "else", "return",
              "var", "this", "delete", "void",
              "typeof", "new", "in", "instanceof",
              "do", "while", "for", "break", "continue",
              "switch", "case", "default", "throw",
              "try", "catch", "finally",
              "function", "debugger", "with",
              "class", "const", "enum", "export",
              "extends", "import", "super",
              "implements", "interface", "let",
              "package", "private", "protected",
              "public", "static", "yield",
              "await", "async",
              -> cb(Token.IDENT, st, input.offset)
            }
          }
      }
    }
  }

  /**
   * 一段高亮区间：[token] 类别，对应 `input[start until end]`。
   */
  data class Span(
    val token: Token,
    val start: Int,
    val end: Int,
  )

  /**
   * 把 `input` 切成高亮区间 —— 纯数据出口，颜色映射交给 UI 侧（`soko.ekibun.acg.ui.comp.highlight`）。
   * 两个 `external fun` 的 JNI 符号挂在 `soko.ekibun.quickjs.Highlight` 这个 FQN 上，
   * 别改包名 / 类名（改了 `quickjs.dll` 不重编即失效，见 cxx/AGENTS.md 的 JNI 一节）。
   */
  fun tokenize(input: String): List<Span> {
    val spans = mutableListOf<Span>()
    parseCurry(TokenParser(input), false) { tok, st, ed ->
      spans.add(Span(tok, st, ed))
    }
    return spans
  }
}
