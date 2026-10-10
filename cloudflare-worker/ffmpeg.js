/**
 * Sanin FFmpeg engine mirror.
 *
 * Serves small manifest + ABI-specific zip packages from Cloudflare KV so the APK itself can ship
 * without a bundled FFmpeg. Files are immutable once uploaded; bump the package version by
 * uploading new objects under a new key set.
 *
 *  GET /manifest.json            -> { "version": "...", "artifacts": {...} }
 *  GET /ffmpeg/<abi>.zip         -> ABI package (arm64-v8a | armeabi-v7a)
 *  HEAD /ffmpeg/<abi>.zip        -> headers only
 *  GET /health                   -> status
 */

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type",
};

function json(body, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method;

    if (method === "OPTIONS") {
      return new Response(null, { status: 204, headers: CORS });
    }

    if (method === "GET" && url.pathname === "/health") {
      return json({ ok: true, service: "sanin-ffmpeg" });
    }

    if (url.pathname.endsWith("/manifest.json")) {
      if (method !== "GET" && method !== "HEAD") {
        return json({ error: "method not allowed" }, 405);
      }
      const manifest = await env.FFMPEG_KV.get("manifest.json", { type: "text" });
      if (!manifest) return json({ error: "manifest not found" }, 404);
      const body = method === "HEAD" ? null : manifest;
      return new Response(body, {
        headers: {
          "Content-Type": "application/json",
          "Cache-Control": "public, max-age=3600",
          "Content-Length": String(new TextEncoder().encode(manifest).byteLength),
          ...CORS,
        },
      });
    }

    const route = url.pathname.match(/^\/ffmpeg\/(arm64-v8a|armeabi-v7a)\.zip$/);
    if (route) {
      if (method !== "GET" && method !== "HEAD") {
        return json({ error: "method not allowed" }, 405);
      }
      const key = `${route[1]}.zip`;
      const object = await env.FFMPEG_KV.get(key, { type: "arrayBuffer" });
      if (!object) return json({ error: "package not found" }, 404);

      const headers = {
        "Content-Type": "application/zip",
        "Cache-Control": "public, max-age=31536000, immutable",
        "Content-Length": String(object.byteLength),
        ...CORS,
      };
      return new Response(method === "HEAD" ? null : object, { headers });
    }

    return json({ error: "not found" }, 404);
  },
};
