rootProject.name = "cloudstream-extensions"

File(rootDir, ".").eachDir { dir ->
    if (!dir.name.startsWith(".") && !dir.name.startsWith("temp_") && File(dir, "build.gradle.kts").exists()) {
        include(dir.name)
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
