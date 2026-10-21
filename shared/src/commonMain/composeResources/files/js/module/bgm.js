// Bangumi（bgm.tv）的 OAuth 登录与收藏读取：端点、参数名与 OAuth 各步骤。
//
// 可用全局：fetch / Settings / openBrowser / FormData / console，以及动态 `import()`。
//
// 键空间归属见 docs/design/bgm-plugin.md：宿主写凭据，插件写登录态。下列三个键名与 `BgmSource`
// 内的常量一致。

const API_BASE = "https://api.bgm.tv";
const OAUTH_BASE = "https://bgm.tv/oauth";

const CLIENT_ID_KEY = "bgm.client_id";
const CLIENT_SECRET_KEY = "bgm.client_secret";
const SESSION_KEY = "bgm.session";

// 每个请求携带的 UA（<dev>/<app>/<version> (<url>)）。
const USER_AGENT = "ekibun/acg/0.1 (https://github.com/ekibun/ACG)";

/** `application/x-www-form-urlencoded` 的表单体。 */
const formBody = (fields) =>
  Object.keys(fields)
    .map((key) => encodeURIComponent(key) + "=" + encodeURIComponent(fields[key]))
    .join("&");

/**
 * 拆开 `openBrowser` 交回的回调 URL，逐段解码后返回普通对象。
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
 * 当前登录态（换回来的 token）；未登录或存档损坏时返回 `null`。
 */
export async function session() {
  const raw = await Settings.get(SESSION_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw);
  } catch (e) {
    return null;
  }
}

/** 退出登录：清掉登录态，保留凭据。 */
export async function logout() {
  await Settings.set(SESSION_KEY, null);
}

async function requestToken(fields) {
  // 令牌端点按表单提交（application/x-www-form-urlencoded）。
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
 * `state` 是本次登录现生成的随机串，回调返回时比对。
 */
export async function login() {
  const clientId = await Settings.get(CLIENT_ID_KEY);
  const clientSecret = await Settings.get(CLIENT_SECRET_KEY);
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
    // redirect_uri 取回调地址去掉查询串。
    redirect_uri: callbackUrl.split("?")[0]
  });
  await Settings.set(SESSION_KEY, JSON.stringify(token));
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
 * 某个用户的收藏列表。`subjectType` 与 `type` 取 v0 的整数枚举（书籍/动画/音乐/游戏/三次元 =
 * 1/2/3/4/6；想看/看过/在看/搁置/抛弃 = 1..5）。
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
