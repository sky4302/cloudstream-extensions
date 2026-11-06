# CloudStream Extension Guidelines

When developing or debugging CloudStream providers and extractors in this repository:

1. **Poster Asset CDN Separation**:
   - Streaming sites often host images on separate CDNs/subdomains rather than the `mainUrl`.
   - Never blindly pass raw relative poster paths to `fixUrlNull()`. Inspect the page's `data-thumbnail_url`, `<picture>`, or `<source srcset>` attributes to identify the true image host (e.g. `https://poster.assetsy.de/wp-content/uploads/` for LK21 / LayarKaca).
   - Ignore `data:image/...` placeholder and 1x1 transparent spacer images (e.g. from lazy loading libraries like `lazysizes`) and prefer `data-src` or high-res `srcset`.

2. **Domain Redirections & Series Subdomains**:
   - When scraping aggregators with separate movie and series backends, always verify that secondary domains (e.g. `seriesUrl`) respond with HTTP 200. Follow and handle redirect endpoints (e.g. `/nontondrama` -> `dramamu.lk21.de` -> `tv.nontondrama.vip`).

3. **Multi-Quality Stream Extraction**:
   - For video CDNs returning a single media playlist (e.g. `480.m3u8` from PlayCDN), check whether sister playlists (`720.m3u8`, `1080.m3u8`, `360.m3u8`) are available to provide full quality choices to the user.

4. **Version Bumping**:
   - Always increment `version` in `build.gradle.kts` when releasing a provider fix so CloudStream clients detect and apply the update.
