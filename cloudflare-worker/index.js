const ANIVAULT_BASE = "https://www.anivault.co/api/mobile";
const TTL_AIRING = 21600;    // 6 hours — airing shows refresh faster
const TTL_COMPLETED = 86400; // 24 hours — completed shows cache longer

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const title = url.searchParams.get("q");

    if (!title || title.trim().length === 0) {
      return new Response(JSON.stringify({ error: "missing ?q=" }), {
        status: 400,
        headers: { "Content-Type": "application/json" },
      });
    }

    const key = title.toLowerCase().trim();
    const cacheKey = `subdub:${key}`;

    // 1. Check KV cache
    const cached = await env.CACHE.get(cacheKey, { type: "json" });
    if (cached) {
      return new Response(JSON.stringify(cached), {
        headers: {
          "Content-Type": "application/json",
          "X-Cache": "HIT",
        },
      });
    }

    // 2. Fetch from AniVault
    const encoded = encodeURIComponent(title);
    const apiUrl = `${ANIVAULT_BASE}/browse?q=${encoded}`;
    const resp = await fetch(apiUrl, {
      headers: {
        "User-Agent": "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36",
      },
    });

    if (!resp.ok) {
      return new Response(JSON.stringify({ sub: 0, dub: 0, total: 0 }), {
        headers: { "Content-Type": "application/json" },
      });
    }

    const json = await resp.json();
    const arr = json.data;
    if (!arr || arr.length === 0) {
      return new Response(JSON.stringify({ sub: 0, dub: 0, total: 0 }), {
        headers: { "Content-Type": "application/json" },
      });
    }

    // 3. Score-based matching
    const titleLower = key;
    let best = null;

    for (const item of arr) {
      const itemTitle = (item.title || "").toLowerCase().trim();
      const eps = item.episodes || 0;
      const type = item.type || "";
      const isTv = type.toLowerCase() === "tv";

      let titleScore = 0;
      if (itemTitle === titleLower) titleScore = 100;
      else if (itemTitle.startsWith(titleLower) || titleLower.startsWith(itemTitle))
        titleScore = 50;

      const tvScore = isTv ? 30 : 0;
      const score = titleScore + tvScore + (eps > 0 ? 1 : 0);

      if (
        !best ||
        score > best.score ||
        (score === best.score && eps > best.eps)
      ) {
        best = { score, eps, item };
      }
    }

    const item = best ? best.item : arr[0];

    const episodes = item.episodes || 0;
    const aired = item.airedInfo?.aired || 0;
    const total = episodes > 0 ? episodes : aired;
    const sub =
      aired > 0 && total > 0
        ? Math.min(aired, total)
        : aired > 0
        ? aired
        : total;
    const dubbedLangs = item.dubbedLangs || [];
    const dub = dubbedLangs.length > 0 ? sub : 0;

    // Determine if airing: aired < total means not all episodes released yet
    const isAiring = total > 0 && aired > 0 && aired < total;
    const ttl = isAiring ? TTL_AIRING : TTL_COMPLETED;

    const result = { sub, dub, total };

    // 4. Cache in KV with appropriate TTL
    await env.CACHE.put(cacheKey, JSON.stringify(result), {
      expirationTtl: ttl,
    });

    return new Response(JSON.stringify(result), {
      headers: {
        "Content-Type": "application/json",
        "X-Cache": "MISS",
      },
    });
  },
};
