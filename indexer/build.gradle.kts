// Builds the visual card index (one file per game) that the app downloads for picture recognition.
// Runs in GitHub Actions: see .github/workflows/card-index.yml.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

// Share the exact signature code with the app so photos and index entries are computed the same way.
val sharedSources = layout.buildDirectory.dir("shared-src")
val copyShared by tasks.registering(Sync::class) {
    val app = rootProject.file("app/src/main/java/com/hazyoctophant/cardvault")
    from(app) {
        include("model/Enums.kt", "scan/VisualSignature.kt", "scan/CardIndex.kt")
    }
    into(sharedSources.map { it.dir("com/hazyoctophant/cardvault") })
}
sourceSets["main"].kotlin.srcDir(sharedSources)
tasks.named("compileKotlin") { dependsOn(copyShared) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("com.hazyoctophant.cardvault.indexer.IndexBuilderKt")
    applicationDefaultJvmArgs = listOf("-Xmx3g")
}
