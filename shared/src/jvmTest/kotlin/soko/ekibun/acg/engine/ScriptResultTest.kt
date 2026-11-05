package soko.ekibun.acg.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 脚本结果的「还没到位」判定：登录页上脚本就该返回 null 等下一次加载。 */
class ScriptResultTest {
  @Test
  fun nullMeansNotYet() {
    assertTrue((null as String?).hasNoScriptResult())
    assertTrue("null".hasNoScriptResult(), "脚本 return null")
    assertTrue("\"null\"".hasNoScriptResult(), "JSON.stringify(null) 再编码一层")
    assertTrue("\"\"".hasNoScriptResult(), "空串")
  }

  @Test
  fun realValuesCountAsDone() {
    assertFalse("""{"url":"https://bgm.tv/"}""".hasNoScriptResult())
    assertFalse("\"/user/ekibun\"".hasNoScriptResult(), "带引号的字符串是真值")
    assertFalse("false".hasNoScriptResult(), "false 也是结果，不能当没给")
    assertFalse("0".hasNoScriptResult())
  }
}
