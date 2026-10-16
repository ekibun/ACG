# 外部浏览器登录（`openBrowser`）的形态依据

插件要拿用户的授权（OAuth 之类）就得让用户在**真浏览器**里登录 —— 内置 WebView 里登录会被服务商
判成不安全的客户端，而且用户拿不到自己的密码管理器与已登录的会话。这份文档记的是这条链路的契约
与裁定：回调地址从哪来、返回什么形状、哪些事引擎不做。改 `JsEngine.openBrowserAsync` 的返回形状、
改 `soko.ekibun.browser` 的契约、或插件写登录流程之前先读本文；代码里只留裸约束。

## 一、契约

```js
const callbackUrl = await openBrowser(templateUrl);
```

- **`templateUrl`** 是授权地址，回调地址的位置由占位符 `{{callback}}` 标出。引擎把它换成
  `http://127.0.0.1:<端口>/<会话id>` 的**百分号编码**形式（授权链接把它放在 `redirect_uri`
  这种查询串参数里，`: / ? #` 必须编码）。
- **返回值**是浏览器**实际请求的那个完整 URL**，原样、未解码、含查询串：

  ```
  http://127.0.0.1:51987/9f3a4b6c2d1e0f8a7b5c4d3e?code=4808dfb7…&state=probe123
  ```

- 没有模板占位符、或浏览器起不来时 **reject**（[`JSError`](../../platform/src/commonMain/kotlin/soko/ekibun/quickjs/JSError.kt)），
  不回空值 —— 回空值会让插件误判成"用户点了取消"。
- **不超时**：用户可能把浏览器标签挂在后台很久再回来点登录。

插件侧两件必须做对的事：

```js
const cb = await openBrowser(authorizeUrl);
const params = Object.fromEntries(cb.split("?")[1].split("&").map((p) => {
  const [k, v] = p.split("=");
  return [decodeURIComponent(k), decodeURIComponent(v)];   // 逐段解
}));
const token = await fetch(tokenEndpoint, {
  method: "POST",
  body: JSON.stringify({ code: params.code, redirect_uri: cb.split("?")[0], … }),
});
```

1. **换 token 的 `redirect_uri` 取 `cb.split("?")[0]`** —— 它必须与授权请求里那个值逐字相同
   （实测：服务商在换 token 这一步校验它）。
2. **别整串 `decodeURIComponent`** —— 那会把参数值里的 `%26` 解成 `&`，把参数切开；要按段解。

## 二、为什么是回环服务器

| 方案 | 为什么不选 |
| --- | --- |
| **回环重定向（本方案）** | 不需要服务商预注册回调地址，回调就是本进程自己的 socket |
| 自定义 scheme（`myapp://`） | 桌面端会**另起一个进程**，结果还得靠通信送回本进程 —— 多一层，问题没少 |
| 授权码写在页面里让用户手抄 | 不成立 |

实测（Bangumi OAuth 走通全流程）：服务商接受**任意端口 + 任意路径**的回环回调地址，不要求预先
登记；`code` 有效期 60 秒；换 token 时 `redirect_uri` 为必填且必须与授权请求一致。所以端口没必要
固定 —— 固定端口会撞别的程序，而 OS 分配的端口对服务商一样合法。

只绑 `127.0.0.1`：同网段的别人不该能替用户完成这次登录。

## 三、为什么是"常驻服务器 + 路径定位会话"

服务器是**进程级单例**（`ServerSocket(0, backlog, 127.0.0.1)`，`by lazy`），不是每次登录一个：

- 端口要由 socket 决定，每次登录新建端口就等于每次换地址；
- 端口被所有会话共用，**会话就只能靠 URL 路径区分** —— 路径段是 `SecureRandom` 出的 24 位十六进制
  会话 id，其余路径一律 404。多个登录并行互不串扰。

按路径区分不是防患于未然：实测浏览器打过来的请求有**两笔** —— `/<会话id>?code=…&state=…` 和
`/favicon.ico`。把整个地址当会话键的写法会被 favicon 那笔带偏。

## 四、返回值为什么不解析

插件要的是 `code` 与 `state`，引擎却回一整串 URL。理由是**解码归知道语义的一方**：

- 宿主原样转交 request-target 是**无损**的；一旦在宿主侧解析成 `{code, state}`，就得替服务商定
  「`+` 算不算空格」「重复键怎么办」这类规则，猜错一次就静默丢掉参数（`code` 只活 60 秒，
  静默丢掉等于让用户重来一遍）。
- 字节到手的形状与 `location.href` 同语义（原样、已编码），插件那边有 `decodeURIComponent`
  （ECMAScript 内建，这份引擎有）与 `split`，逐段解是它自己的事。
  *注*：`init.js` 里那两个 `encodeURI` / `encodeURIComponent` 是**定制版**（多收一个 charset
  参数），不是补缺；解码侧用的是标准内建。
- 引擎里**不能有 `URLSearchParams` 之类的假设**：这份 QuickJS 只编核心（`cxx/quickjs/quickjs.cmake`
  只收 `quickjs.c` 等核心源文件），`URL` / `URLSearchParams` 是 Web API、不在 ECMAScript 里，
  引擎里没有。

## 五、哪些事引擎不做

- **不做 token 交换**：要 `client_id` / `client_secret`，那是插件自己的知识，引擎里一条服务商
  相关的东西都没有（`client_secret` 也不进本仓库）。
- **不做 implicit 流**（`response_type=token`）：那条路的 token 落在 URL 的 **fragment** 里，
  而 fragment **不会发给服务器**（RFC 3986 §3.5，fragment 不属于 request-target）—— 回环服务器
  物理上收不到。真要收得让回调页面自己读 `location.hash` 再回传，为一个照样会过期的 token 加一个
  活动件不值。
- **不做刷新**：token 过期/失效后的重新授权就是再调一次 `openBrowser`。

## 六、平台差异

| | 实现 | 备注 |
| --- | --- | --- |
| Android | Chrome Custom Tabs（`androidx.browser`） | 从进程级 Context 起 Activity，必须带 `FLAG_ACTIVITY_NEW_TASK`；没装支持 Custom Tabs 的浏览器时那条 Intent 就是普通 `ACTION_VIEW`，系统自己兜 |
| 桌面 (JVM) | `java.awt.Desktop.browse` | 系统默认浏览器；无头环境抛 `IllegalStateException`（消息给人看） |

回调页面（会话兑现后回给浏览器的 200）是一句中文提示 + 尽力而为的 `window.close()`：
`window.close()` 只对脚本开的窗口有效，Custom Tabs 里是空操作，所以**提示语才是真的收尾手段**。

## 七、已知取舍

- **弃掉的登录会留一个常驻会话条目**，直到 `close()`（`openBrowserAsync` 的 `finally` 调）——
  如果插件扔下 Promise 不管、协程也不取消，那条会话就一直等着。这是"不超时"的直接代价：
  换掉它就得给插件一个能主动取消的 handle，而那会把返回形状从"resolve 时给值"改成"先给句柄"。
- **同一个模板连续调两次**会起两个并行会话，两条都等着；没有"同一个授权只允许一次"的约束。

## 八、测试面

| 验什么 | 在哪 |
| --- | --- |
| 回环服务器本身（地址形状、按路径区分、原样交付、撤掉即 404、请求并行不串扰） | `platform/src/jvmTest/kotlin/soko/ekibun/browser/CallbackSessionTest.kt` |
| `openBrowserAsync` 的编排（占位符换成什么、返回什么形状；`launch` 注入，不真开浏览器） | `shared/src/jvmTest/kotlin/soko/ekibun/acg/engine/BrowserLoginTest.kt` |
| JS 侧 `openBrowser` 的真派发（只走模板校验那一支 —— 真跑到底会弹浏览器） | `shared/src/jvmTest/kotlin/soko/ekibun/acg/engine/JsEngineDispatchTest.kt` |
