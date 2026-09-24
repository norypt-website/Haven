// Throwaway init script: generates a CycloneDX SBOM without adding the plugin to the project build.
// Usage: ./gradlew --init-script tools/cyclonedx.init.gradle.kts :app:cyclonedxBom
initscript {
    repositories { gradlePluginPortal() }
    dependencies { classpath("org.cyclonedx:cyclonedx-gradle-plugin:2.3.1") }
}
allprojects {
    if (name == "app") {
        apply<org.cyclonedx.gradle.CycloneDxPlugin>()
    }
}
