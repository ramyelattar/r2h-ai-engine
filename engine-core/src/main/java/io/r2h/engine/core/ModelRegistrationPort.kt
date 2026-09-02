package io.r2h.engine.core

import java.io.File

fun interface ModelRegistrationPort {
    fun registerLocalModel(file: File): String
}
