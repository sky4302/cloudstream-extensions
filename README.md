# CloudStream Extensions: Indo-Malay Streaming Suite

A collection of extensions/plugins for the **[CloudStream 3](https://github.com/recloudstream/cloudstream)** application targeting popular Indonesian and Malay movie, TV series, and anime streaming websites.

---

## 📺 Supported Providers & Target Websites

| Provider | Target Website | Media Types | Language | Key Features |
| :--- | :--- | :--- | :--- | :--- |
| **Idlix** | `https://z2.idlixku.com/movie` | Movies & TV Series | Indonesian (`id`) | API-driven catalog, multi-season & episode support, dynamic gate/claim token resolver, direct `.m3u8` streams, curated carousels (*Latest Movies*, *Latest TV Series*, *Netflix*, *Disney+*, *HBO*, *Apple TV+*, *Amazon Prime*). |
| **LayarKaca (LK21)** | `https://tv12.lk21official.cc/` | Movies, TV Series, Asian Drama | Indonesian (`id`) | Full LayarKaca21 & Nontondrama scraping, homepage carousels (*Film Terpopuler, IMDb Rating, Terbanyak Komentar, Upload Terbaru, Series Terbaru, Film Asian Terbaru*), dynamic `VideoNode` / `playcdn.de` verify API resolution, Hownetwork, and Turbovid HLS extractors. |
| **PencuriMovie** | `https://ww44.pencurimovie.baby/` | Movies & TV Series | Malay (`ms`) | MovieMo WordPress engine, rich category carousels (*Latest Movies*, *TV Series*, *Most Rating*, *Top IMDB*, and country filters: *Malaysia, Indonesia, India, Japan, Thailand, China*). |
| **PencuriMovie Sub Malay** | `https://pencurimoviesubmalay26.site/` | Movies & TV Series | Malay (`ms`) | ZetaFlix engine with multi-server resolution and 12 registered extractors: `DhtprePmsm`, `NetuPmsm`, `Playerxupns`, `Playerxp2p`, `Playerxseek`, `Playerxrpms`, `Player4me`, `Ezplayer`, `YandexcdnPmsm`, `Larhu`, plus remote `dec-abyss` & AES-256-CTR `AbyssExtractor`. |
| **Kuramanime** | `https://m2.kuramanime.ing/` | Anime | Indonesian (`id`) | Ongoing, completed, and popular anime carousels, dynamic tokenized API auth (`X-Fuck-ID`), KuramaDrive API, PixelDrain direct links. |
| **Kuronime** | `https://kuronime.sbs/` | Anime, Movies, OVA | Indonesian (`id`) | Episode updates, ongoing anime, most popular, and anime movie carousels, multi-server iframe extraction (KrakenFiles, PixelDrain). |
| **Otakudesu** | `https://otakudesu.blog/` | Anime, Movies, OVA | Indonesian (`id`) | Ongoing & completed anime carousels, DesuStream mirror AJAX resolution with nonce token extraction, Archive.org direct MP4 extraction, and download mirror redirect resolution. |

---

## 📱 How to Add Repository to CloudStream 3

### Fast Setup (TV Remote / Short URL)
Use the short URL designed for easy typing on Android TV remotes:
```text
https://is.gd/skycs3
```

### Direct Repository URL
```text
https://raw.githubusercontent.com/sky4302/cloudstream-extensions/builds/repo.json
```

### Steps to Install:
1. Open **CloudStream 3** on your Android device (TV Stick, TV Box, or smartphone).
2. Navigate to **Settings** (⚙️) ➔ **Extensions** ➔ **Add Repository**.
3. Enter:
   - **Repository Name**: `IndoMalay Streams`
   - **Repository URL**: `https://is.gd/skycs3`
4. Tap **Download Repository**.
5. Go to the Extensions tab and click **Install** on your desired providers:
   - `Idlix`
   - `LayarKaca`
   - `PencuriMovie`
   - `PencuriMovieSubMalay`
   - `Kuramanime`
   - `Kuronime`
   - `Otakudesu`

---

## 🛠️ Project Structure

```text
cloudstream repo/
├── Idlix/                      # Idlix extension (API catalog, streaming platforms)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Idlix.kt
│       ├── IdlixParser.kt
│       └── IdlixPlugin.kt
├── LayarKaca/                  # LayarKaca21 (LK21) extension (VideoNode, PlayCDN)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── LayarKacaProvider.kt
│       ├── LayarKacaProviderPlugin.kt
│       └── Extractors.kt
├── PencuriMovie/               # PencuriMovie extension (MovieMo)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── PencuriMovie.kt
│       └── PencuriMoviePlugin.kt
├── PencuriMovieSubMalay/        # PencuriMovie Sub Malay extension (ZetaFlix)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── PencuriMovieSubMalay.kt
│       ├── PencuriMovieSubMalayPlugin.kt
│       └── Extractors.kt
├── Kuramanime/                 # Kuramanime extension
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Kuramanime.kt
│       └── KuramanimePlugin.kt
├── Kuronime/                   # Kuronime extension
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Kuronime.kt
│       └── KuronimePlugin.kt
├── Otakudesu/                  # Otakudesu extension (replaces NontonAnimeID)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Otakudesu.kt
│       └── OtakudesuPlugin.kt
├── build.gradle.kts            # Root build configuration
├── settings.gradle.kts         # Root settings module includes
├── plugins.json                # Plugin registry manifest
├── repo.json                   # Repository configuration file
└── gradlew.bat / gradlew       # Gradle wrapper executable
```

---

## 🚀 Building & Development

### Prerequisites
- **Java JDK 17** (e.g. Eclipse Temurin or OpenJDK 17)
- **Android SDK** (API Platform 33 / Build Tools)

### 1. Build All Plugins
```powershell
.\gradlew.bat make
```
Compiles all providers into `.cs3` packages located in the `build/` directory of each subproject.

### 2. Generate Manifest (`plugins.json` and `repo.json`)
```powershell
.\gradlew.bat makePluginsJson
```
Calculates SHA-256 hashes and metadata for all compiled plugins and outputs the updated `build/plugins.json`.

### 3. Build a Specific Plugin
```powershell
# Build Idlix only:
.\gradlew.bat Idlix:make

# Build PencuriMovieSubMalay only:
.\gradlew.bat PencuriMovieSubMalay:make

# Build Otakudesu only:
.\gradlew.bat Otakudesu:make
```

### 4. Direct Deploy via ADB (TV Stick / Mobile)
Connect your device with USB or Wireless ADB:
```powershell
adb connect <device-ip>:5555
.\gradlew.bat Idlix:deployWithAdb
```

---

## ⚖️ Disclaimer & License

This project is intended for educational and personal use only. The extensions in this repository do not host or store any media files; they merely parse publicly accessible web interfaces. All copyrights belong to their respective owners.
