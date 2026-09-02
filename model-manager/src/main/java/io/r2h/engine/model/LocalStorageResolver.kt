package io.r2h.engine.model

import android.content.Context
import java.io.File

class LocalStorageResolver(context: Context) {
    private val root = File(context.filesDir, "models").canonicalFile.also { it.mkdirs() }

    fun resolveInsideModels(fileName: String): File? {
        val candidate = File(root, fileName).canonicalFile
        return if (candidate.startsWith(root)) candidate else null
    }

    fun isInsideModels(file: File): Boolean = file.canonicalFile.startsWith(root)

    fun rootDirectory(): File = root
}
