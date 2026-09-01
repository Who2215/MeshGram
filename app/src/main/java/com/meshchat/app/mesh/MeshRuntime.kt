package com.meshchat.app.mesh

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object MeshRuntime {
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var managerRef: BleMeshManager? = null

    fun manager(context: Context): BleMeshManager {
        val existing = managerRef
        if (existing != null) return existing
        return synchronized(this) {
            managerRef ?: BleMeshManager(
                context = context.applicationContext,
                scope = runtimeScope
            ).also { managerRef = it }
        }
    }
}
