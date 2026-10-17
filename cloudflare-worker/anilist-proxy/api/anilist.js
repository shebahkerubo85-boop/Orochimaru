// AniList GraphQL relay for the sanin-push Cloudflare Worker.
//
// AniList blocks Cloudflare Workers' shared egress IPs, so the worker cannot
// query graphql.anilist.co directly. This function runs on Vercel's Node.js
// serverless runtime (AWS Lambda — NOT Vercel Edge, which runs on Cloudflare),
// so requests leave from an IP AniList accepts.
//
// Deploy: put this repo's `anilist-proxy` folder on Vercel (Framework preset:
// "Other"). The endpoint is https://<project>.vercel.app/api/anilist. Set that
// as ANILIST_PROXY on the sanin-push worker.

export default async function handler(req, res) {
  if (req.method === "OPTIONS") {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.status(204).end();
    return;
  }

  let body = "";
  await new Promise((resolve) => {
    req.on("data", (chunk) => {
      body += chunk;
    });
    req.on("end", resolve);
  });

  try {
    const upstream = await fetch("https://graphql.anilist.co", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body,
    });
    const text = await upstream.text();
    res.status(upstream.status);
    res.setHeader("Content-Type", "application/json");
    res.send(text);
  } catch (e) {
    res.status(502).json({ error: String(e) });
  }
}
