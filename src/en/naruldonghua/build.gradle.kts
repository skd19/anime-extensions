plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "NarulDonghua"
    versionCode = 1
    contentWarning = ContentWarning.SAFE
    libVersion = "16"
    theme = "animestream"

    source {
        lang = "en"
        baseUrl = "https://naruldonghua.com"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:rumbleextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:playlistutils"))
}
