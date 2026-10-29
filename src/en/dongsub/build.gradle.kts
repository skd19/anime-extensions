plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Dongsub"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://www.dongsub.net"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:youtubeextractor"))
    implementation(project(":lib:playlistutils"))
}
