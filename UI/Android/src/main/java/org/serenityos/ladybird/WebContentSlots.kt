/**
 * Copyright (c) 2025, the Ladybird developers.
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * A fixed pool of WebContent processes, one per live tab (like Chrome's sandboxed renderer slots).
 *
 * Android runs each service class in the process the manifest gives it, so every slot is its own
 * service class. When all slots are taken, the browser discards a background tab to free one.
 */
object WebContentSlots {
    private val serviceClasses = arrayOf(
        WebContentService0::class.java,
        WebContentService1::class.java,
        WebContentService2::class.java,
        WebContentService3::class.java,
        WebContentService4::class.java,
        WebContentService5::class.java,
    )
    val count get() = serviceClasses.size

    lateinit var resourceDir: String
    private val inUse = BooleanArray(serviceClasses.size)
    private val releasedAt = LongArray(serviceClasses.size)

    val freeCount get() = inUse.count { !it }

    class Slot(val index: Int, private val context: Context, private val connection: LadybirdServiceConnection) {
        private var references = 1

        fun retain() {
            references++
        }

        fun release() {
            if (--references > 0)
                return
            // Unbinding makes the service end its process (see WebContentService.onUnbind).
            connection.onDisconnect = {}
            try {
                context.unbindService(connection)
            } catch (e: IllegalArgumentException) {
                Log.w("WebContentSlots", "Slot $index was not bound", e)
            }
            inUse[index] = false
            releasedAt[index] = SystemClock.elapsedRealtime()
        }
    }

    /** Binds a free WebContent process and hands it the IPC socket. Returns null when every slot is taken. */
    fun acquire(context: Context, ipcFd: Int, onCrash: () -> Unit): Slot? {
        // Prefer the slot that was freed longest ago, so its old process has had time to exit.
        val index = serviceClasses.indices.filter { !inUse[it] }.minByOrNull { releasedAt[it] }
        if (index == null) {
            Log.e("WebContentSlots", "No free WebContent slot")
            android.os.ParcelFileDescriptor.adoptFd(ipcFd).close()
            return null
        }

        val appContext = context.applicationContext
        val connection = LadybirdServiceConnection(ipcFd, resourceDir)
        connection.onDisconnect = {
            Log.e("WebContentSlots", "WebContent process in slot $index died")
            onCrash()
        }
        inUse[index] = true
        appContext.bindService(Intent(appContext, serviceClasses[index]), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        return Slot(index, appContext, connection)
    }
}
