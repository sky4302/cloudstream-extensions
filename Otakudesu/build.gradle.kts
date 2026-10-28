version = 1

cloudstream {
    language = "id"
    description = "Otakudesu - Nonton Anime Subtitle Indonesia"
    authors = listOf("sky4302")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://otakudesu.blog&sz=%size%"
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/sky4302/cloudstream-extensions")
}
