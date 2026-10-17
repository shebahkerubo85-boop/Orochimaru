/**
 * Orochimaru push relay.
 *
 *  POST /subscribe    { token, anilist?: number[], tmdb?: number[] }
 *  POST /unsubscribe  { token }
 *  GET  /health
 *  GET  /count
 *  GET  /debug?key=<admin>   (diagnostics, no secrets returned)
 *
 *  scheduled() runs every 15 minutes: looks for episodes that just aired for the
 *  shows each device follows and sends an FCM message. Subscribers are processed
 *  in slices (see SLICE_SUBS/CURSOR_KEY) so each invocation stays within the
 *  Workers Free per-invocation limits; a full cycle covers everyone over several
 *  ticks.
 *
 * Configuration is read from KV key `cfg:config` (see README notes). Values may also
 * come from env bindings (FCM_PROJECT_ID / FCM_CLIENT_EMAIL / FCM_PRIVATE_KEY /
 * ADMIN_KEY / TMDB_API_KEY) as a fallback. Binding: SUBS (KV namespace PUSH_SUBS).
 */

const ANILIST_ENDPOINT = "https://graphql.anilist.co";
const WINDOW_BACK = 12 * 3600; // seconds of airing history to scan
const WINDOW_FWD = 30 * 60;
const TMDB_WINDOW_DAYS = 3;
const ID_BATCH = 50;

// Sharding so a single invocation stays within the Workers Free limits
// (50 external subrequests, 10 ms CPU). State is stored in D1 (`subs` + `meta`),
// not KV: KV's free plan caps writes/lists at 1000/day, which this sweep would
// exhaust on its own. Each cron tick handles one slice of subscribers and
// advances a cursor, so a full cycle covers everyone across several ticks.
const SLICE_SUBS = 20; // subscribers handled per invocation
const MAX_ANILIST_IDS = 300; // AniList IDs queried per invocation (6 batches)
const MAX_TMDB_FETCHES = 30; // TMDB lookups per invocation
const MAX_SENDS = 12; // FCM sends per invocation
const CURSOR_KEY = "meta:cursor";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

function b64url(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function b64urlStr(str) {
  return b64url(new TextEncoder().encode(str));
}

function pemToDer(pem) {
  const b64 = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

async function signJwt(payload, privateKeyPem) {
  const input =
    b64urlStr(JSON.stringify({ alg: "RS256", typ: "JWT" })) +
    "." +
    b64urlStr(JSON.stringify(payload));
  const key = await crypto.subtle.importKey(
    "pkcs8",
    pemToDer(privateKeyPem),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    key,
    new TextEncoder().encode(input),
  );
  return input + "." + b64url(new Uint8Array(sig));
}

// One access token per isolate; refreshed a minute before it expires.
let cachedAccess = { value: null, exp: 0 };

let cfgCache = null;

async function loadConfig(env) {
  if (cfgCache) return cfgCache;
  let kv = null;
  try {
    kv = (await env.SUBS.get("cfg:config", { type: "json" })) || {};
  } catch {
    kv = {};
  }
  const cfg = {
    projectId: kv.projectId || env.FCM_PROJECT_ID || "",
    clientEmail: kv.clientEmail || env.FCM_CLIENT_EMAIL || "",
    privateKey: kv.privateKey || env.FCM_PRIVATE_KEY || "",
    adminKey: kv.adminKey || env.ADMIN_KEY || "",
    tmdbKey: kv.tmdbKey || env.TMDB_API_KEY || "",
    anilistProxy: kv.anilistProxy || env.ANILIST_PROXY || "",
  };
  anilistProxy = cfg.anilistProxy;
  cfgCache = cfg;
  return cfg;
}

async function getAccessToken(cfg) {
  if (cachedAccess.value && Date.now() < cachedAccess.exp - 60_000) {
    return cachedAccess.value;
  }
  const now = Math.floor(Date.now() / 1000);
  const jwt = await signJwt(
    {
      iss: cfg.clientEmail,
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
      iat: now,
      exp: now + 3600,
    },
    cfg.privateKey,
  );
  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=${jwt}`,
  });
  if (!res.ok) {
    throw new Error(`token exchange failed: ${res.status} ${await res.text()}`);
  }
  const data = await res.json();
  cachedAccess = {
    value: data.access_token,
    exp: Date.now() + (data.expires_in || 3600) * 1000,
  };
  return cachedAccess.value;
}

/** Returns "ok", "dead" (token no longer registered) or "error". */
async function sendFcm(cfg, token, payload) {
  const access = await getAccessToken(cfg);
  const res = await fetch(
    `https://fcm.googleapis.com/v1/projects/${cfg.projectId}/messages:send`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${access}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ message: { token, ...payload } }),
    },
  );
  if (res.ok) return "ok";
  const text = await res.text();
  if (res.status === 404 || /UNREGISTERED|INVALID_REGISTRATION|NOT_FOUND/.test(text)) {
    return "dead";
  }
  console.log(`FCM ${res.status}: ${text}`);
  return "error";
}

function notificationPayload(title, body, image, data) {
  return {
    notification: { title, body, ...(image ? { image } : {}) },
    data: Object.fromEntries(
      Object.entries(data).map(([k, v]) => [k, String(v)]),
    ),
    android: {
      priority: "high",
      notification: {
        channel_id: "anime_push",
        icon: "notification_icon",
        ...(image ? { image } : {}),
        color: "#039BE5",
        click_action: "ani.sanin.OPEN_MEDIA",
        visibility: "public",
      },
    },
  };
}

// ---------------------------------------------------------------- AniList

let lastAniListError = null;

// AniList outright blocks Cloudflare Workers' shared egress IPs (403 "You have
// been manually blocked"; AniList staff: "Most Cloudflare Workers have been
// blocked"). When ANILIST_PROXY is set, GraphQL requests are sent there so they
// leave from a non-Cloudflare IP. The relay just forwards the POST body to
// https://graphql.anilist.co and returns the response verbatim.
let anilistProxy = "";

async function queryAiring(min, max, ids) {
  const query = `query ($min: Int, $max: Int, $ids: [Int]) {
    Page(page: 1, perPage: 100) {
      airingSchedules(airingAt_greater: $min, airingAt_lesser: $max, mediaId_in: $ids, sort: TIME) {
        mediaId
        episode
        airingAt
        media {
          id
          status
          episodes
          title { romaji english native }
          coverImage { large }
        }
      }
    }
  }`;
  const init = {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      "User-Agent": "Sanin-Push/1.0 (+https://github.com/Shippunn/sanin)",
    },
    body: JSON.stringify({ query, variables: { min, max, ids } }),
  };
  const endpoint = anilistProxy || ANILIST_ENDPOINT;
  const ATTEMPTS = 3;
  for (let attempt = 1; attempt <= ATTEMPTS; attempt++) {
    let res;
    try {
      res = await fetch(endpoint, init);
    } catch (e) {
      lastAniListError = `fetch: ${e}`;
      if (attempt < ATTEMPTS) {
        await new Promise((r) => setTimeout(r, 400 * attempt));
        continue;
      }
      console.log(`AniList fetch failed: ${e}`);
      return [];
    }
    if (res.status === 403 || res.status === 429 || res.status >= 500) {
      lastAniListError = `http ${res.status}`;
      if (attempt < ATTEMPTS) {
        await new Promise((r) => setTimeout(r, 400 * attempt));
        continue;
      }
      console.log(`AniList ${res.status}: ${await res.text()}`);
      return [];
    }
    if (!res.ok) {
      lastAniListError = `http ${res.status}`;
      console.log(`AniList ${res.status}`);
      return [];
    }
    const json = await res.json();
    if (json.errors) {
      lastAniListError = JSON.stringify(json.errors);
      console.log(`AniList errors: ${lastAniListError}`);
      return [];
    }
    lastAniListError = null;
    return json.data?.Page?.airingSchedules || [];
  }
  return [];
}

async function anilistHits(min, max, ids) {
  const out = [];
  for (let i = 0; i < ids.length; i += ID_BATCH) {
    const chunk = ids.slice(i, i + ID_BATCH);
    out.push(...(await queryAiring(min, max, chunk)));
  }
  return out;
}

// ---------------------------------------------------------------- TMDB

async function tmdbHits(cfg, ids, max = 40) {
  if (!cfg.tmdbKey || ids.length === 0) return [];
  const out = [];
  const cutoff = new Date(Date.now() - TMDB_WINDOW_DAYS * 86400_000)
    .toISOString()
    .slice(0, 10);
  for (const id of ids.slice(0, max)) {
    try {
      const res = await fetch(
        `https://api.themoviedb.org/3/tv/${id}?api_key=${cfg.tmdbKey}&language=en-US`,
      );
      if (!res.ok) continue;
      const show = await res.json();
      const ep = show.last_episode_to_air;
      if (!ep || !ep.air_date || ep.air_date < cutoff) continue;
      out.push({
        id,
        name: show.name,
        poster: show.poster_path
          ? `https://image.tmdb.org/t/p/w500${show.poster_path}`
          : "",
        season: ep.season_number,
        episode: ep.episode_number,
        episodeTitle: ep.name || "",
        airDate: ep.air_date,
      });
    } catch (e) {
      console.log(`tmdb ${id}: ${e}`);
    }
  }
  return out;
}

// ---------------------------------------------------------------- storage
//
// Subscription state lives in D1 (table `subs`), not KV. KV's free plan caps
// writes and lists at 1000/day, which the every-2-minutes sweep blew through
// regardless of subscriber count. D1's free plan allows ~100k row writes and
// 5M row reads per day, so the sweep scales to hundreds of users.

function rowToSub(r) {
  let a = [];
  let m = [];
  let n = {};
  try {
    a = JSON.parse(r.anilist) || [];
  } catch {
    /* corrupt, treat as none */
  }
  try {
    m = JSON.parse(r.tmdb) || [];
  } catch {
    /* corrupt, treat as none */
  }
  try {
    n = JSON.parse(r.noted) || {};
  } catch {
    /* corrupt, treat as none */
  }
  return { token: r.token, key: `sub:${r.token}`, a, m, n, c: r.created };
}

async function loadSubs(env, after, limit) {
  const stmt = after
    ? env.DB.prepare(
        "SELECT token, anilist, tmdb, noted, created FROM subs WHERE token > ? ORDER BY token LIMIT ?",
      ).bind(after, limit)
    : env.DB.prepare(
        "SELECT token, anilist, tmdb, noted, created FROM subs ORDER BY token LIMIT ?",
      ).bind(limit);
  const { results } = await stmt.all();
  return (results || []).map(rowToSub);
}

async function countSubs(env) {
  const row = await env.DB.prepare("SELECT COUNT(*) AS c FROM subs").first();
  return row ? row.c : 0;
}

async function getSub(env, token) {
  return env.DB.prepare("SELECT noted, created FROM subs WHERE token = ?")
    .bind(token)
    .first();
}

async function upsertSub(env, token, anilist, tmdb, noted, created) {
  await env.DB.prepare(
    "INSERT INTO subs (token, anilist, tmdb, noted, created) VALUES (?, ?, ?, ?, ?) " +
      "ON CONFLICT(token) DO UPDATE SET anilist = excluded.anilist, tmdb = excluded.tmdb, noted = excluded.noted",
  )
    .bind(
      token,
      JSON.stringify(anilist),
      JSON.stringify(tmdb),
      JSON.stringify(noted),
      created,
    )
    .run();
}

async function setNoted(env, token, noted) {
  await env.DB.prepare("UPDATE subs SET noted = ? WHERE token = ?")
    .bind(JSON.stringify(noted), token)
    .run();
}

async function deleteSub(env, token) {
  await env.DB.prepare("DELETE FROM subs WHERE token = ?").bind(token).run();
}

// ---------------------------------------------------------------- cron

// Sweep progress cursor: p = KV list page cursor, a = AniList id window offset,
// t = TMDB id window offset. Windows rotate so every subscriber's shows are
// eventually checked while each invocation stays within the Free plan budget.
function freshCursor() {
  return { p: "", a: 0, t: 0 };
}

async function readCursor(env) {
  const row = await env.DB.prepare("SELECT v FROM meta WHERE k = ?")
    .bind(CURSOR_KEY)
    .first();
  if (!row || !row.v) return freshCursor();
  try {
    const v = JSON.parse(row.v);
    return {
      p: typeof v.p === "string" ? v.p : "",
      a: Number.isInteger(v.a) ? v.a : 0,
      t: Number.isInteger(v.t) ? v.t : 0,
    };
  } catch {
    // Legacy plain-string cursor from an earlier deploy.
    return { p: row.v.startsWith("sub:") ? row.v : "", a: 0, t: 0 };
  }
}

async function writeCursor(env, c) {
  await env.DB.prepare(
    "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v",
  )
    .bind(CURSOR_KEY, JSON.stringify(c))
    .run();
}

async function runSweep(env) {
  const cfg = await loadConfig(env);

  const state = await readCursor(env);
  const subs = await loadSubs(env, state.p || "", SLICE_SUBS);

  if (subs.length === 0) {
    if (state.p) await writeCursor(env, freshCursor());
    return { subs: 0, sent: 0, dead: 0 };
  }

  const endReached = subs.length < SLICE_SUBS;
  const lastToken = subs[subs.length - 1].token;

  const allAnilist = [...new Set(subs.flatMap((s) => s.a || []))];
  const allTmdb = [...new Set(subs.flatMap((s) => s.m || []))];

  const now = Math.floor(Date.now() / 1000);
  const anilistIds = allAnilist.slice(state.a, state.a + MAX_ANILIST_IDS);
  const tmdbIds = allTmdb.slice(state.t, state.t + MAX_TMDB_FETCHES);
  lastAniListError = null;
  const airing = anilistIds.length
    ? await anilistHits(now - WINDOW_BACK, now + WINDOW_FWD, anilistIds)
    : [];
  const shows = await tmdbHits(cfg, tmdbIds);

  let sent = 0;
  const dead = [];
  let processedAll = true;

  for (const sub of subs) {
    const noted = sub.n || {};
    const nextNoted = { ...noted };
    const jobs = [];
    const followedA = new Set(sub.a || []);
    const followedM = new Set(sub.m || []);

    for (const a of airing) {
      if (!followedA.has(a.mediaId)) continue;
      const prev = noted[`a${a.mediaId}`] || 0;
      if (a.episode <= prev) continue;
      const title =
        a.media?.title?.english || a.media?.title?.romaji || a.media?.title?.native || "New episode";
      jobs.push({
        note: [`a${a.mediaId}`, a.episode],
        payload: notificationPayload(
          "New episode available",
          `${title} · Episode ${a.episode}`,
          a.media?.coverImage?.large || "",
          {
            type: "airing",
            source: "anilist",
            mediaId: a.mediaId,
            episode: a.episode,
            title,
            cover: a.media?.coverImage?.large || "",
          },
        ),
      });
    }

    for (const show of shows) {
      if (!followedM.has(show.id)) continue;
      const key = `t${show.id}`;
      const val = `S${show.season}E${show.episode}`;
      if (noted[key] === val) continue;
      const label = show.episodeTitle ? ` (${show.episodeTitle})` : "";
      jobs.push({
        note: [key, val],
        payload: notificationPayload(
          "New episode available",
          `${show.name} · S${show.season}E${show.episode}${label}`,
          show.poster,
          {
            type: "airing",
            source: "tmdb",
            tmdbId: show.id,
            season: show.season,
            episode: show.episode,
            title: show.name,
            cover: show.poster,
          },
        ),
      });
    }

    if (jobs.length === 0) continue;

    // Out of send budget: stop here without advancing past this subscriber so
    // its remaining jobs are retried on the next tick.
    if (sent >= MAX_SENDS) {
      processedAll = false;
      break;
    }

    const toSend = jobs.slice(0, MAX_SENDS - sent);
    let subDead = false;
    for (const job of toSend) {
      const r = await sendFcm(cfg, sub.t, job.payload);
      if (r === "dead") {
        subDead = true;
        break;
      }
      if (r === "ok") {
        sent++;
        nextNoted[job.note[0]] = job.note[1];
      }
    }

    if (subDead) {
      dead.push(sub.token);
    } else {
      // Persist progress for exactly the jobs that were sent; anything not sent
      // stays unnoted and is retried next tick without duplication.
      await setNoted(env, sub.token, nextNoted);
    }
  }

  for (const token of dead) await deleteSub(env, token);

  // If the send budget cut the slice short, retry the same page and windows.
  // Otherwise advance the AniList/TMDB windows, and only move to the next page
  // once both windows have been fully swept (already-sent jobs are suppressed
  // via each subscriber's `n`).
  let next;
  if (!processedAll) {
    next = state;
  } else {
    const a = state.a + MAX_ANILIST_IDS;
    const t = state.t + MAX_TMDB_FETCHES;
    const anilistDone = a >= allAnilist.length;
    const tmdbDone = t >= allTmdb.length;
    if (anilistDone && tmdbDone) {
      next = { p: endReached ? "" : lastToken, a: 0, t: 0 };
    } else {
      next = { p: state.p || "", a: anilistDone ? 0 : a, t: tmdbDone ? 0 : t };
    }
  }
  await writeCursor(env, next);

  return {
    subs: subs.length,
    sent,
    dead: dead.length,
    airing: airing.length,
    anilist: !anilistIds.length
      ? "skip"
      : lastAniListError
        ? `error: ${lastAniListError}`
        : "ok",
  };
}

// ---------------------------------------------------------------- http

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return json({ ok: true, time: Date.now() });
    }

    if (request.method === "POST" && url.pathname === "/subscribe") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const token = typeof body.token === "string" ? body.token.trim() : "";
      if (!token || token.length < 100 || token.length > 512) {
        return json({ error: "bad token" }, 400);
      }
      const anilist = Array.isArray(body.anilist)
        ? [...new Set(body.anilist.filter((x) => Number.isInteger(x)))]
        : [];
      const tmdb = Array.isArray(body.tmdb)
        ? [...new Set(body.tmdb.filter((x) => Number.isInteger(x)))]
        : [];

      const existing = await getSub(env, token);
      let noted = {};
      let created = 0;
      if (existing) {
        try {
          noted = JSON.parse(existing.noted) || {};
        } catch {
          /* start fresh */
        }
        created = existing.created || 0;
      } else {
        created = Math.floor(Date.now() / 1000);
      }

      await upsertSub(env, token, anilist, tmdb, noted, created);
      return json({ ok: true, anilist: anilist.length, tmdb: tmdb.length });
    }

    if (request.method === "POST" && url.pathname === "/unsubscribe") {
      let body;
      try {
        body = await request.json();
      } catch {
        return json({ error: "bad json" }, 400);
      }
      const token = typeof body.token === "string" ? body.token.trim() : "";
      if (token) await deleteSub(env, token);
      return json({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/count") {
      return json({ subscriptions: await countSubs(env) });
    }

    // Diagnostics for the maintainer: checks FCM auth and the AniList query
    // without sending anything. Requires ?key= equal to the admin key.
    if (request.method === "GET" && url.pathname === "/debug") {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      const out = {
        fcm: "skipped",
        anilist: null,
        tmdb: Boolean(cfg.tmdbKey),
        cfg: {
          projectSet: Boolean(cfg.projectId),
          emailSet: Boolean(cfg.clientEmail),
          key: cfg.privateKey ? { len: cfg.privateKey.length } : null,
        },
      };
      try {
        await getAccessToken(cfg);
        out.fcm = "ok";
      } catch (e) {
        out.fcm = String(e.message || e);
      }
      try {
        const now = Math.floor(Date.now() / 1000);
        const sample = await queryAiring(now - WINDOW_BACK, now + WINDOW_FWD, [1]);
        out.anilist = `${sample.length} schedules${
          lastAniListError ? ` (last error: ${lastAniListError})` : ""
        }`;
      } catch (e) {
        out.anilist = String(e.message || e);
      }
      out.subscriptions = await countSubs(env);
      return json(out);
    }

    // Runs the notification sweep on demand (admin only).
    if (
      (request.method === "GET" || request.method === "POST") &&
      url.pathname === "/sweep"
    ) {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      const result = await runSweep(env);
      return json(result);
    }

    // Manual test push (admin only). Sends a notification to every subscriber,
    // or a single one via ?token=. Handy for verifying FCM end-to-end.
    if (request.method === "GET" && url.pathname === "/test") {
      const cfg = await loadConfig(env);
      if (cfg.adminKey && url.searchParams.get("key") !== cfg.adminKey) {
        return json({ error: "forbidden" }, 403);
      }
      const only = url.searchParams.get("token");
      const subs = only ? [{ token: only }] : await loadSubs(env, "", 1000);
      const results = [];
      for (const sub of subs) {
        const r = await sendFcm(
          cfg,
          sub.token,
          notificationPayload(
            "Sanin push test",
            "If you can see this, notifications are working.",
            "",
            { type: "test" },
          ),
        );
        results.push({ token: String(sub.token).slice(0, 16), result: r });
      }
      return json({ count: subs.length, sent: results });
    }

    return json({ error: "not found" }, 404);
  },

  async scheduled(_event, env) {
    const result = await runSweep(env);
    console.log(`sweep: ${JSON.stringify(result)}`);
    return result;
  },
};