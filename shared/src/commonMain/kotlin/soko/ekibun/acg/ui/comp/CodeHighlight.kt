package soko.ekibun.acg.ui.comp

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import soko.ekibun.quickjs.Highlight

/** 把 [Highlight.tokenize] 的区间映射成颜色并上样式。 */
fun highlight(input: String): AnnotatedString {
  val builder = AnnotatedString.Builder()
  builder.append(input)
  Highlight.tokenize(input).forEach { span ->
    when (span.token) {
      Highlight.Token.STRING -> SpanStyle(color = Color.Magenta)
      Highlight.Token.COMMENT -> SpanStyle(color = Color.Green)
      Highlight.Token.REGEXP -> SpanStyle(color = Color.Red)
      Highlight.Token.IDENT -> SpanStyle(color = Color.Blue)
    }.let { builder.addStyle(it, span.start, span.end) }
  }
  return builder.toAnnotatedString()
}
