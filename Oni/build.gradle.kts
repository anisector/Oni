// v37: playback reliability, safe source headers and parser regression tests.
version = 37
cloudstream {
    description = "Anizium provider - verified embed success, safe playback headers and parser regression tests"
    authors = listOf("kerimmkirac", "ByAyzen", "anisector")
    status = 1
    tvTypes = listOf("Anime", "AnimeMovie")
    language = "tr"
    iconUrl = "https://anizium.co/assets/images/logo.png"
}

dependencies {
    add("testImplementation", "junit:junit:4.13.2")
}
