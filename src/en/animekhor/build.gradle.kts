plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "AnimeKhor"
    versionCode = 23
    contentWarning = ContentWarning.SAFE
    libVersion = "16"

    source {
        lang = "en"
        baseUrl = "https://animekhor.org"
    }
}

dependencies {
    implementation(project(":lib:dailymotionextractor"))
    implementation(project(":lib:doodextractor"))
    implementation(project(":lib:mp4uploadextractor"))
    implementation(project(":lib:okruextractor"))
    implementation(project(":lib:playlistutils"))
    implementation(project(":lib:rumbleextractor"))
    implementation(project(":lib:streamwishextractor"))
    implementation(project(":lib:vidhideextractor"))
}
