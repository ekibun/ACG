// Bangumi（bgm.tv）插件：登录、验人、打开主页、读收藏。
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

/**
 * 收藏键 -> 站点路径 `<类型>/<状态>`。键是宿主原样画、原样传回的那串文字，值是插件翻回去的路径段。
 * 字直接列、不拼：各类型叫法不一样（书「在读」、音乐「在听」、游戏「在玩」）。顺序即界面顺序，
 * 进行中排第一 —— 第一档是默认档。
 */
const COLLECTIONS = {
  "动画|在看": "anime/do",
  "动画|想看": "anime/wish",
  "动画|看过": "anime/collect",
  "动画|搁置": "anime/on_hold",
  "动画|抛弃": "anime/dropped",
  "书籍|在读": "book/do",
  "书籍|想读": "book/wish",
  "书籍|读过": "book/collect",
  "书籍|搁置": "book/on_hold",
  "书籍|抛弃": "book/dropped",
  "音乐|在听": "music/do",
  "音乐|想听": "music/wish",
  "音乐|听过": "music/collect",
  "音乐|搁置": "music/on_hold",
  "音乐|抛弃": "music/dropped",
  "游戏|在玩": "game/do",
  "游戏|想玩": "game/wish",
  "游戏|玩过": "game/collect",
  "游戏|搁置": "game/on_hold",
  "游戏|抛弃": "game/dropped",
  "三次元|在看": "real/do",
  "三次元|想看": "real/wish",
  "三次元|看过": "real/collect",
  "三次元|搁置": "real/on_hold",
  "三次元|抛弃": "real/dropped"
};

// 追番块的 `subject_type` → 那一档的键。站点只给书 / 动画 / 真人建块，音乐与游戏没有。
const HOME_BLOCK_KEY = { "1": "书籍|在读", "2": "动画|在看", "6": "三次元|在看" };

/**
 * 带会话取一页 HTML：网页端认 cookie，不另找 token —— 会话里 @ 本站的那条原样塞进 `Cookie`。
 *
 * 验人（设置页）与收藏（主页追番块、各档列表页）都从这里走，差别只在 URL。
 */
const fetchHtml = async (session, url) => {
  const response = await fetch(url, { headers: { Cookie: cookiesOf(session)[BASE] || "" } });
  if (!response.ok) throw new Error("bangumi " + url + " failed (" + response.status + ")");
  return response.text();
};

/** 站点给的是协议相对地址（`//lain.bgm.tv/…`）或站内路径，补成完整 URL。 */
const absolute = (url) =>
  !url ? null : url.startsWith("//") ? "https:" + url : url.startsWith("/") ? BASE + url : url;

/** 封面 / 头像：`src` 优先，其次懒加载的 `data-cfsrc`，最后是内联样式里的 background-image。 */
const imageOf = (node) =>
  absolute(
    node.attr("src") ||
      node.attr("data-cfsrc") ||
      (/background-image:url\('([^']*)'\)/.exec(node.attr("style") || "") || [])[1]
  );

/** 写一条 cookie：域固定成 `.bgm.tv`，与站点 `Set-Cookie` 落进 jar 的那条一致（见 §5.9）。 */
const setCookie = (url, cookie) => Cookies.set(url, cookie + "; Domain=." + HOST + "; Path=/");

/** 让本站点已有的 cookie 全部过期：jar 只有一份，换人之前先腾干净，免得旧账号的残留混进来。 */
const expireSiteCookies = async () => {
  for (const pair of (await Cookies.get(BASE)).split(";")) {
    const name = pair.split("=")[0].trim();
    if (name) await setCookie(BASE, name + "=; Max-Age=0");
  }
};

/** 走一遍站点登录页（前台），离开登录页即收尾，再抓设置页验人。 */
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

  // 以「站点认得出这个人」为准：拿不到用户就让登录失败，不建空号。设置页固定二十来 KB，
  // 主页随收藏与动态涨到几百 KB，页头却是同一份（v0 的 `/v0/me` 要 OAuth token，这里只有 cookie）
  const $ = load(await fetchHtml(session, BASE + "/settings"));
  // 页头在就是登着；未登录时这块只有 `.guest` 的登录/注册
  const avatarLink = $(".idBadgerNeue a.avatar").first();
  if (!avatarLink.length) throw new Error("bangumi 未登录：页头只有登录/注册");
  const id = (/\/user\/([^/]*)/.exec(avatarLink.attr("href") || "") || [])[1];
  if (!id) throw new Error("bangumi 用户链接里没有用户名");
  // 昵称是设置表单里的一个字段；账号没设昵称就落回用户名
  const name = ($('input[name="nickname"]').first().attr("value") || "").trim() || id;

  return { id, name, avatar: imageOf(avatarLink.find("span.avatarNeue").first()), session };
}

/** 打开站点主页：先把 jar 置成本账号的会话（页面一打开就认这个人），窗口留给用户自己关。 */
export async function openHome(account) {
  await expireSiteCookies();
  for (const [url, raw] of Object.entries(cookiesOf(account.session))) {
    for (const cookie of raw.split(";").map((value) => value.trim())) {
      if (cookie) await setCookie(url, cookie);
    }
  }
  return webview(BASE, { show: true });
}

/**
 * 收藏：不带 `key` 探一次，带 `key` 要一档的一页。两边返回形状一样：
 * `{ "<键>": { data, page, pageTotal } }`，键是插件给宿主的那串文字，宿主原样存、原样传回。
 *
 * 不带 key：主页能一次给全的三档（在看在读在玩）给完整列表（`pageTotal` 为 1，块不在就是空），
 * 其余给 `undefined`（宿主落成 `null`）—— 那是「还不知道」，不是「空」。主页抓不到就让调用失败，
 * 不编一份全 `undefined` 的键表，那会把「请求挂了」说成「没有收藏」。
 *
 * 带 key：只要那一档，但主页顺手解出的另外两档也一起给；不是那三档就翻列表页，`data` 只是那一页。
 *
 * `account` 是宿主整个账号（`Account.toPluginParam`）：路径里的用户取 `id`，认 cookie 取 `session`。
 */
export async function collections(account, key, page = 1) {
  // 要的那一档在追番块里（或没指定）才抓主页；抓一次就把三档都拿在手上
  const scopes =
    !key || Object.values(HOME_BLOCK_KEY).includes(key)
      ? parseHomeCollections(await fetchHtml(account.session, BASE + "/"))
      : null;

  // 追番块就是这三档的全部：整档列全，块不在即这类没有在看在读 —— 一样算读完，宿主不必再为它来一趟
  const known = {};
  if (scopes) {
    for (const scope of Object.values(HOME_BLOCK_KEY)) {
      known[scope] = { page: 1, pageTotal: 1, data: scopes[scope] || [] };
    }
  }

  if (!key) {
    const all = {};
    for (const scope of Object.keys(COLLECTIONS)) all[scope] = known[scope];
    return all;
  }
  // 不是那三档（主页上也没顺带解出来）：照常翻列表页
  if (!known[key]) known[key] = await listPage(account, key, page);
  return known;
}

/**
 * 一档的一页：`/<类型>/list/<用户>/<状态>?page=N`，条目在 `#browserItemList > li.item`。
 *
 * `pageTotal` 取自这一页的分页条（没有就是 1），每页重算 —— 翻的中途页数变了，最多让宿主多要或
 * 少要一页，下一次续读自然对齐。宿主不必等全集，插件也不替它持有。追番块那三档不走这里。
 */
async function listPage(account, key, page) {
  const path = COLLECTIONS[key];
  if (!path) throw new Error("bangumi 不认识的收藏分类：" + key);
  const [type, status] = path.split("/");

  const url = BASE + "/" + type + "/list/" + encodeURIComponent(account.id) + "/" + status;
  // 第一页的地址不带参数：站点自己也会把 `?page=1` 落到同一个列表
  const $ = load(await fetchHtml(account.session, page > 1 ? url + "?page=" + page : url));

  let pageTotal = 1;
  $("#multipage a[href*='page=']").each((_, node) => {
    const found = /[?&]page=(\d+)/.exec($(node).attr("href") || "");
    if (found) pageTotal = Math.max(pageTotal, Number(found[1]));
  });

  // `name` 是原名、`nameCn` 是中文名、`summary` 是那行灰字
  const data = $("#browserItemList > li.item")
    .map((_, node) => {
      const li = $(node);
      const id = Number((li.attr("id") || "").split("_")[1]);
      const nameCn = li.find("h3 a").first().text().trim();
      const name = li.find("h3 small").first().text().trim() || nameCn;
      if (!id || !name) return null;
      return {
        id,
        name,
        nameCn: nameCn || null,
        summary: li.find("p.info.tip").first().text().trim() || null,
        image: imageOf(li.find("img").first())
      };
    })
    .get()
    .filter(Boolean);

  return { page, pageTotal, data };
}

/**
 * 从主页追番块解出全部进行中的条目，按 `类型|在X` 分组 —— 动画 / 书籍 / 真人都在同一张主页上。
 *
 * 每块是 `.infoWrapper[subject_type]`，条目带 `data-subject-id` / `-name` / `-name-cn`，进度在
 * `.progress .inner small`（`6/12`、`5/??`）。块里就是这一档的全部，不分页；缺的那块就是这类
 * 没有在看在读，交给 [collections] 当空档。
 */
function parseHomeCollections(html) {
  const $ = load(html);
  const scopes = {};
  $(".infoWrapper").each((_, node) => {
    const block = $(node);
    // 键直接由块的类型定：`书籍|在读` 这种，不拼
    const key = HOME_BLOCK_KEY[block.attr("subject_type")];
    if (!key) return;
    scopes[key] = scopes[key] || [];
    const link = block.find(".headerInner h3 a").first();
    const id = Number(link.attr("data-subject-id"));
    const name = (link.attr("data-subject-name") || "").trim();
    if (!id || !name) return;
    scopes[key].push({
      id,
      name,
      nameCn: (link.attr("data-subject-name-cn") || "").trim() || null,
      summary: null,
      image: imageOf(block.find("img").first()),
      progress: block.find(".progress .inner small").first().text().trim() || null
    });
  });
  // 块里就是这一档的全部，不分页
  return scopes;
}
