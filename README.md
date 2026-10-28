# CloudStream Extensions: Indo-Malay Streaming Suite

Koleksi ekstensi / plugin untuk aplikasi **[CloudStream 3](https://github.com/recloudstream/cloudstream)** yang menargetkan situs streaming film, serial drama, dan anime berbahasa Indonesia dan Melayu.

---

## 📺 Supported Providers & Target Websites

| Provider | Target Website | Tipe Konten | Bahasa | Fitur Utama |
| :--- | :--- | :--- | :--- | :--- |
| **Idlix** | `https://z2.idlixku.com/movie` | Film & Serial TV | Indonesia (`id`) | API-driven catalog, multi-musim, episode resolver, stream gate/claim session resolver, m3u8 direct streams |
| **PencuriMovie** | `https://ww44.pencurimovie.baby/` | Film & Siri TV | Melayu (`ms`) | MovieMo WordPress engine, pagination kategori (movies, series, rating, country), iframe resolver |
| **PencuriMovie Sub Malay** | `https://pencurimoviesubmalay26.site/` | Film & Drama Melayu | Melayu (`ms`) | ZetaFlix engine, parsing musim & episod, AJAX player option resolver (`zeta_player_ajax`), Abyss/RPMPlay/P2PStream extractor |
| **Kuramanime** | `https://m2.kuramanime.ing/` | Anime | Indonesia (`id`) | Katalog tayang/selesai/movie, episode pagination, KuramaDrive auth token token generator, PixelDrain downloader |
| **Kuronime** | `https://kuronime.sbs/` | Anime, Movie, OVA | Indonesia (`id`) | New episode feeds, anime detail slug parser, multi-server iframe extraction, KrakenFiles & PixelDrain download link |
| **NontonAnimeID** | `https://s13.nontonanimeid.boats/` | Anime, Movie, OVA | Indonesia (`id`) | Katalog anime lengkap, Cloudflare bypass support, player AJAX server extractor (Gdplayer, KotakAnime, Filesim, Hxfile) |

---

## 🛠️ Project Structure

```text
cloudstream repo/
├── Idlix/                      # Ekstensi Idlix
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Idlix.kt
│       └── IdlixPlugin.kt
├── PencuriMovie/               # Ekstensi PencuriMovie (MovieMo)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── PencuriMovie.kt
│       └── PencuriMoviePlugin.kt
├── PencuriMovieSubMalay/        # Ekstensi PencuriMovie Sub Malay (ZetaFlix)
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── PencuriMovieSubMalay.kt
│       ├── PencuriMovieSubMalayPlugin.kt
│       └── Extractors.kt
├── Kuramanime/                 # Ekstensi Kuramanime
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Kuramanime.kt
│       └── KuramanimePlugin.kt
├── Kuronime/                   # Ekstensi Kuronime
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── Kuronime.kt
│       └── KuronimePlugin.kt
├── NontonAnimeID/              # Ekstensi NontonAnimeID
│   ├── build.gradle.kts
│   └── src/main/kotlin/com/hexated/
│       ├── NontonAnimeIDProvider.kt
│       ├── NontonAnimeIDPlugin.kt
│       ├── AnimeMetadata.kt
│       └── Extractors.kt
├── build.gradle.kts            # Root build configuration
├── settings.gradle.kts         # Root settings module includes
├── plugins.json                # Plugin registry manifest
├── repo.json                   # Repository configuration file
└── gradlew.bat / gradlew       # Gradle wrapper executable
```

---

## 🚀 Cara Build / Kompilasi Ekstensi

Pastikan perangkat Anda memiliki:
- **Java JDK 17** (Temurin / OpenJDK 17)
- **Android SDK** (API Platform 33 / Build Tools)

### 1. Build Semua Plugin Sekaligus
```powershell
.\gradlew.bat make
```
Perintah ini akan mengkompilasi seluruh provider menjadi paket file `.cs3` di folder `build/` masing-masing subproject.

### 2. Generate Manifest `plugins.json`
```powershell
.\gradlew.bat makePluginsJson
```
Perintah ini menghitung SHA-256 hash dan metadata setiap plugin yang telah dibuild dan menyimpannya di `build/plugins.json`.

### 3. Build Plugin Spesifik
```powershell
# Contoh build Idlix saja:
.\gradlew.bat Idlix:make

# Contoh build PencuriMovieSubMalay saja:
.\gradlew.bat PencuriMovieSubMalay:make
```

### 4. Deploy Langsung ke HP / Emulator Android via ADB
Sambungkan HP Android Anda via USB Debugging, lalu jalankan:
```powershell
.\gradlew.bat Idlix:deployWithAdb
```

---

## 📱 Cara Menambahkan Repository ke CloudStream

1. Buka aplikasi **CloudStream 3** di HP Android Anda.
2. Buka menu **Settings** (Ikon Gerigi) ➔ **Extensions** (Ekstensi).
3. Klik tombol **Add Repository** (Tambah Repositori).
4. Masukkan nama repository (contoh: `IndoMalay Stream`).
5. Masukkan URL ke file `repo.json` repositori Anda di GitHub:
   ```text
   https://raw.githubusercontent.com/<username>/<repo>/builds/repo.json
   ```
6. Simpan, kemudian buka tab Extensions untuk menginstal provider yang diinginkan (`Idlix`, `PencuriMovie`, `PencuriMovieSubMalay`, `Kuramanime`, `Kuronime`, atau `NontonAnimeID`).
