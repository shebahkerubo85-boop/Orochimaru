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
 *  shows each device follows and sends an FCM message.
 *
 * Configuration is read from KV key `cfg:config` (see README notes). Values may also
 * come from env bindings (FCM_PROJECT_ID / FCM_CLIENT_EMAIL / FCM_PRIVATE_KEY /
 * ADMIN_KEY / TMDB_API_KEY) as a fallback. Binding: SUBS (KV namespace PUSH_SUBS).
 */

const ANILIST_ENDPOINT = "https://graphql.anilist.co";
const WINDOW_BACK = 6 * 3600; // seconds of airing history to scan
const WINDOW_FWD = 30 * 60;
const TMDB_WINDOW_DAYS = 3;
const ID_BATCH = 50;
const MAX_PUSHES_PER_SUB = 25;

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
  };
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
        ...(image ? { image } : {}),
        color: "#039BE5",
        click_action: "ani.sanin.OPEN_MEDIA",
        visibility: "public",
      },
    },
  };
}

// ---------------------------------------------------------------- AniList

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
  const res = await fetch(ANILIST_ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ query, variables: { min, max, ids } }),
  });
  if (!res.ok) {
    console.log(`AniList ${res.status}: ${await res.text()}`);
    return [];
  }
  const json = await res.json();
  if (json.errors) {
    console.log(`AniList errors: ${JSON.stringify(json.errors)}`);
    return [];
  }
  return json.data?.Page?.airingSchedules || [];
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

async function tmdbHits(cfg, ids) {
  if (!cfg.tmdbKey || ids.length === 0) return [];
  const out = [];
  const cutoff = new Date(Date.now() - TMDB_WINDOW_DAYS * 86400_000)
    .toISOString()
    .slice(0, 10);
  for (const id of ids.slice(0, 40)) {
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

async function allSubs(env) {
  const subs = [];
  let cursor;
  do {
    const page = await env.SUBS.list({
      prefix: "sub:",
      limit: 1000,
      cursor,
    });
    for (const key of page.keys) {
      const raw = await env.SUBS.get(key.name);
      if (!raw) continue;
      try {
        subs.push({ key: key.name, ...JSON.parse(raw) });
      } catch {
        /* unreadable entry, ignore */
      }
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);
  return subs;
}

// ---------------------------------------------------------------- cron

async function runSweep(env) {
  const cfg = await loadConfig(env);
  const subs = await allSubs(env);
  if (subs.length === 0) return { subs: 0, sent: 0 };

  const allAnilist = new Set();
  const allTmdb = new Set();
  for (const s of subs) {
    for (const id of s.a || []) allAnilist.add(id);
    for (const id of s.m || []) allTmdb.add(id);
  }

  const now = Math.floor(Date.now() / 1000);
  const airing = allAnilist.size
    ? await anilistHits(now - WINDOW_BACK, now + WINDOW_FWD, [...allAnilist])
    : [];
  const shows = await tmdbHits(cfg, [...allTmdb]);

  let sent = 0;
  const dead = [];

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
      nextNoted[`a${a.mediaId}`] = a.episode;
      const title =
        a.media?.title?.english || a.media?.title?.romaji || a.media?.title?.native || "New episode";
      jobs.push(
        notificationPayload(
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
      );
    }

    for (const show of shows) {
      if (!followedM.has(show.id)) continue;
      const key = `t${show.id}`;
      const val = `S${show.season}E${show.episode}`;
      if (noted[key] === val) continue;
      nextNoted[key] = val;
      const label = show.episodeTitle ? ` (${show.episodeTitle})` : "";
      jobs.push(
        notificationPayload(
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
      );
    }

    if (jobs.length === 0) continue;

    let subDead = false;
    for (const job of jobs.slice(0, MAX_PUSHES_PER_SUB)) {
      const r = await sendFcm(cfg, sub.t, job);
      if (r === "dead") {
        subDead = true;
        break;
      }
      if (r === "ok") sent++;
    }
    if (subDead) {
      dead.push(sub.key);
    } else {
      await env.SUBS.put(sub.key, JSON.stringify({ ...sub, key: undefined, n: nextNoted }));
    }
  }

  for (const key of dead) await env.SUBS.delete(key);
  return { subs: subs.length, sent, dead: dead.length };
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

      const existingRaw = await env.SUBS.get(`sub:${token}`);
      let noted = {};
      let created = 0;
      if (existingRaw) {
        try {
          const existing = JSON.parse(existingRaw);
          noted = existing.n || {};
          created = existing.c || 0;
        } catch {
          /* start fresh */
        }
      } else {
        created = Math.floor(Date.now() / 1000);
      }

      await env.SUBS.put(
        `sub:${token}`,
        JSON.stringify({ t: token, a: anilist, m: tmdb, n: noted, c: created }),
      );
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
      if (token) await env.SUBS.delete(`sub:${token}`);
      return json({ ok: true });
    }

    if (request.method === "GET" && url.pathname === "/count") {
      const subs = await allSubs(env);
      return json({ subscriptions: subs.length });
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
        out.anilist = `ok (${sample.length} schedules for media 1)`;
      } catch (e) {
        out.anilist = String(e.message || e);
      }
      out.subscriptions = (await allSubs(env)).length;
      return json(out);
    }

    return json({ error: "not found" }, 404);
  },

  async scheduled(_event, env) {
    try {
      const result = await runSweep(env);
      console.log(`sweep: ${JSON.stringify(result)}`);
      return result;
    } catch (e) {
      console.log(`sweep failed: ${e}`);
      throw e;
    }
  },
};