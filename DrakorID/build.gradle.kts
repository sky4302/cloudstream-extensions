version = 4

cloudstream {
    language = "id"
    description = "DrakorID - Nonton Streaming Drama Korea, Drama Asia & Movie Sub Indo"
    authors = listOf("sky4302")
    status = 1
    tvTypes = listOf("AsianDrama", "Movie")
    iconUrl = "https://www.google.com/s2/favicons?domain=https://drakorid.co&sz=%size%"
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/sky4302/cloudstream-extensions")
}
