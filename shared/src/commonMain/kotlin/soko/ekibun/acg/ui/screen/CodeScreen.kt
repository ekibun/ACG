package soko.ekibun.acg.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import soko.ekibun.acg.engine.JsEngine
import soko.ekibun.quickjs.Highlight
import soko.ekibun.quickjs.JSError

@Preview(showBackground = true)
@Composable
fun CodeScreen() {
    var text by remember { mutableStateOf(TextFieldValue()) }
    var evalval by remember { mutableStateOf("") }

    Column {
        TextField(
            value = text,
            onValueChange = {
                text = TextFieldValue(Highlight.highlight(it.text), it.selection, it.composition)
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .weight(1f)
        )
        Row {
            Button(onClick = {
                MainScope().launch {
                    evalval = try {
                        val ret = JsEngine.instance.evaluate(text.text)
                        if (ret is Deferred<*>) ret.await() else ret
                    } catch (e: JSError) {
                        e
                    }.toString()
                }
            }) { Text("Run>") }
        }
        Text(
            text = evalval, modifier = Modifier
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
                .weight(1f)
        )
    }
}