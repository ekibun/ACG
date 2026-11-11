// Bangumi（bgm.tv）插件：登录、验人、打开主页。
//
// 分层与实测结论见 docs/design/bgm-plugin.md 和 docs/development/shared-layer.md §5.9。
// 可用全局：fetch / Cookies / Settings / webview / FormData / console，以及同目录模块。

import { load } from "cheerio";

const BASE = "https://bgm.tv";
const LOGIN = BASE + "/login";

// `Domain=` 要写的那一份：站点只有一个域，账号 cookie 全挂在它上面。
const HOST = BASE.replace(/^https?:\/\//, "");

// 登录流程自己的跳转：留在这几条里继续加载，跳出去才算「登完了」；站点新增登录子页要加进来
const LOGIN_STAYS = ["/login", "/signup", "/FollowTheRabbit", "/reset_password", "/RedPill"];
const staysOnLogin = (url) => LOGIN_STAYS.some((path) => url.startsWith(BASE + path));

/** 会话里的 `url → "n=v; …"` 表：形状是本插件自己定的 `{ cookies: { "<url>": "n=v; ..." } }`。 */
const cookiesOf = (session) => JSON.parse(session).cookies || {};

/** 写一条 cookie：域固定成 `.bgm.tv`，与站点 `Set-Cookie` 落进 jar 的那条一致（见 §5.9）。 */
const setCookie = (url, cookie) => Cookies.set(url, cookie + "; Domain=." + HOST + "; Path=/");

/** 让本站点已有的 cookie 全部过期：jar 只有一份，换人之前先腾干净，免得旧账号的残留混进来。 */
const expireSiteCookies = async () => {
  for (const pair of (await Cookies.get(BASE)).split(";")) {
    const name = pair.split("=")[0].trim();
    if (name) await setCookie(BASE, name + "=; Max-Age=0");
  }
};

/** 走一遍站点登录页（前台），离开登录页即收尾；成败交给 [me] 验。 */
export async function login() {
  // jar 里还留着上一个账号的登录态时，登录页会直接认出那个人
  await expireSiteCookies();
  const landed = await webview(LOGIN, {
    show: true,
    onNavigate: (url) => (staysOnLogin(url) ? false : { url })
  });
  // 用户自己关了页面：宿主兑现 undefined
  if (!landed) throw new Error("bangumi 登录页已关闭，登录取消");

  const cookie = await Cookies.get(BASE);
  if (!cookie) throw new Error("bangumi 登录页没有留下 cookie");
  const session = JSON.stringify({ cookies: { [BASE]: cookie } });

  // 以「站点认得出这个人」为准：拿不到用户就让登录失败，不建空号
  const user = await me(session);
  return { userId: user.userId, name: user.name, avatar: user.avatar, session };
}

/** 打开站点主页：先把 jar 置成本账号的会话（页面一打开就认这个人），窗口留给用户自己关。 */
export async function openHome(session) {
  await expireSiteCookies();
  for (const [url, raw] of Object.entries(cookiesOf(session))) {
    for (const cookie of raw.split(";").map((value) => value.trim())) {
      if (cookie) await setCookie(url, cookie);
    }
  }
  return webview(BASE, { show: true });
}

/**
 * 从主页 HTML 认当前用户：`.idBadgerNeue a.avatar` 在就是登着，用户名取其 href 的 `/user/([^/]*)`，
 * 昵称取 `#header a`（站点已无这个 id，实际落回用户名）。判据与账号 id 的取法见 bgm-plugin §四。
 */
export function parseMe(html) {
  const $ = load(html);
  const avatarLink = $(".idBadgerNeue a.avatar").first();
  if (!avatarLink.length) {
    throw new Error("bangumi 未登录：页头只有登录/注册");
  }

  const username = (/\/user\/([^/]*)/.exec(avatarLink.attr("href") || "") || [])[1];
  if (!username) throw new Error("bangumi 用户链接里没有用户名");
  const nickname = $("#header a").first().text().trim();

  const avatar = avatarLink.find("span.avatarNeue").first();
  let avatarUrl =
    avatar.attr("src") ||
    avatar.attr("data-cfsrc") ||
    (/background-image:url\('([^']*)'\)/.exec(avatar.attr("style") || "") || [])[1];
  if (avatarUrl && avatarUrl.startsWith("//")) avatarUrl = "https:" + avatarUrl;
  else if (avatarUrl && avatarUrl.startsWith("/")) avatarUrl = BASE + avatarUrl;

  return {
    userId: username,
    name: nickname || username,
    avatar: avatarUrl || null
  };
}

/** 抓一次主页交给 [parseMe]；v0 要 OAuth token，这里只有网页登录的 cookie。 */
export async function me(session) {
  const response = await fetch(BASE + "/", {
    headers: { Cookie: cookiesOf(session)[BASE] || "" }
  });
  if (!response.ok) throw new Error("bangumi home failed (" + response.status + ")");
  return parseMe(await response.text());
}
