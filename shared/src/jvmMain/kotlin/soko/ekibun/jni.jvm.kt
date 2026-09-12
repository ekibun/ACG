package soko.ekibun

import java.io.File

@Suppress("UnsafeDynamicallyLoadedCode")
actual fun jniLoadLibrary(name: String) {
    val osName = System.getProperty("os.name").lowercase()
    val extension = when {
        osName.contains("win") -> ".dll"
        osName.contains("mac") -> ".dylib"
        else -> ".so"
    }
    val fileName = if (osName.contains("win")) "$name$extension" else "lib$name$extension"

    // ClassLoader 会自动在 build/run/main/classpath/classes/ 中定位文件
    val inputStream = (Thread.currentThread().contextClassLoader
        ?: object {}.javaClass.classLoader).getResourceAsStream(fileName)

    if (inputStream != null) {
        val tempFile = File.createTempFile("native_", "_$fileName")
        tempFile.deleteOnExit()
        inputStream.use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        System.load(tempFile.absolutePath)
    } else {
        System.loadLibrary(name)
    }
}
