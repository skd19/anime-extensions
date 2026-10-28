plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Anime4i"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "animestream"

    source {
        lang = "en"
        baseUrl = "https://anime4i.com"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
}
