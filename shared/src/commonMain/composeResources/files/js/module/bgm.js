// Bangumi（bgm.tv）的 OAuth 登录与收藏读取 —— 第一个跑在宿主能力之上的插件。
//
// 这里只放**服务商知识**：端点、参数名、OAuth 的每一步。把应答折成界面要的形状归 Kotlin
// 的 `BgmSource` —— 拼串在 JS 侧没有编译期检查，而这一侧剩下的活（URL、表单、Bearer 头）
// 本来就全是字符串。
//
// 能用的全局只有 init.js 定义的那几个：fetch / settings / openBrowser / FormData / console。
// 动态 `import()` 也通（宿主每次调用现 import 一次本模块）。
//
// 键空间的归属见 docs/design/bgm-plugin.md：**宿主只写凭据，插件只写登录态**。
// 下面这三个键名与 `BgmSource` 里的常量是同一份，改一处就得改两处。

const API_BASE = "https://api.bgm.tv";
const OAUTH_BASE = "https://bgm.tv/oauth";

const CLIENT_ID_KEY = "bgm.client_id";
const CLIENT_SECRET_KEY = "bgm.client_secret";
const SESSION_KEY = "bgm.session";

// Bangumi 要求每个请求都带 UA，格式 <dev>/<app>/<version> (<url>)。
// 缺了不是降级：整站回 403「Please set a 'User-Agent'」（实测）。
const USER_AGENT = "ekibun/acg/0.1 (https://github.com/ekibun/ACG)";

/** `application/x-www-form-urlencoded` 的表单体。 */
const formBody = (fields) =>
  Object.keys(fields)
    .map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(fields[key]))
    .join("&");

/**
 * 拆开 `openBrowser` 交回来的回调 URL，**逐段**解码。
 *
 * 逐段是必须的：整串 `decodeURIComponent` 会把某个值里的 `%26` 解成 `&`，把参数切开。
 * 返回的是普通对象 —— `Object.fromEntries` 在这份引擎里有，但 `URLSearchParams` 没有。
 */
export function parseCallback(callbackUrl) {
  const query = callbackUrl.split("?")[1];
  if (!query) throw new Error("oauth callback carries no query string: " + callbackUrl);
  const params = {};
  for (const pair of query.split("&")) {
    const separator = pair.indexOf("=");
    const key = separator < 0 ? pair : pair.slice(0, separator);
    const value = separator < 0 ? "" : pair.slice(separator + 1);
    params[decodeURIComponent(key)] = decodeURIComponent(value);
  }
  return params;
}

/**
 * 当前登录态（换回来的 token 那一坨），没登录返回 `null`。
 *
 * 存档坏了也当没登录：让用户重登一次，好过揣着半个对象去请求。
 */
export async function session() {
  const raw = await settings.get(SESSION_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

/** 退出登录：只清登录态，凭据留着（下次登录还用得上）。 */
export async function logout() {
  await settings.set(SESSION_KEY, null);
}

async function requestToken(fields) {
  // 令牌端点在老 PHP 站上，编码按 RFC 6749 §4.1.3 用表单（实测 JSON 也收，但表单是规定动作）。
  const response = await fetch(OAUTH_BASE + "/access_token", {
    method: "POST",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      "User-Agent": USER_AGENT
    },
    body: formBody(fields)
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error("oauth token request failed (" + response.status + "): " + body);
  }
  return JSON.parse(body);
}

/**
 * 走一遍授权码流程，把换到的 token 存进 `bgm.session` 并返回它。
 *
 * `state` 是自己出的随机串：回环服务器只绑 127.0.0.1、会话 id 又是宿主出的 24 位随机路径，
 * 别人本来也够不着这次回调；这里是别让**手边的**页面把回调打进来。
 */
export async function login() {
  const clientId = await settings.get(CLIENT_ID_KEY);
  const clientSecret = await settings.get(CLIENT_SECRET_KEY);
  if (!clientId || !clientSecret) {
    throw new Error("bangumi client_id / client_secret are not set; fill them in the collect tab first");
  }

  const state = Date.now() + "-" + Math.random().toString(36).slice(2);
  const callbackUrl = await openBrowser(
    OAUTH_BASE +
      "/authorize?client_id=" +
      encodeURIComponent(clientId) +
      "&response_type=code&state=" +
      encodeURIComponent(state) +
      "&redirect_uri={{callback}}"
  );

  const params = parseCallback(callbackUrl);
  if (params.state !== state) throw new Error("oauth state mismatch; login aborted");
  if (!params.code) throw new Error("oauth callback carries no code");

  const token = await requestToken({
    grant_type: "authorization_code",
    client_id: clientId,
    client_secret: clientSecret,
    code: params.code,
    // 必须与授权请求里那个 redirect_uri 逐字相同 —— 就是回调地址去掉查询串。
    redirect_uri: callbackUrl.split("?")[0]
  });
  await settings.set(SESSION_KEY, JSON.stringify(token));
  return token;
}

async function authorizedJson(path) {
  const current = await session();
  if (!current || !current.access_token) throw new Error("not logged in to bangumi");
  const response = await fetch(API_BASE + path, {
    headers: {
      "User-Agent": USER_AGENT,
      Authorization: "Bearer " + current.access_token
    }
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error("bangumi api " + path + " failed (" + response.status + "): " + body);
  }
  return JSON.parse(body);
}

/** 当前登录用户。 */
export async function me() {
  return await authorizedJson("/v0/me");
}

/**
 * 某个用户的收藏列表。
 *
 * `subjectType` 与 `type` 都是 v0 的**整数**枚举（书籍/动画/音乐/游戏/三次元 = 1/2/3/4/6；
 * 想看/看过/在看/搁置/抛弃 = 1..5），不是老 API 那套 `wish` / `do` 字符串。
 */
export async function collections(username, subjectType, type, offset, limit) {
  return await authorizedJson(
    "/v0/users/" +
      encodeURIComponent(username) +
      "/collections?subject_type=" +
      subjectType +
      "&type=" +
      type +
      "&offset=" +
      offset +
      "&limit=" +
      limit
  );
}
