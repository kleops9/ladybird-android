/**
 * Copyright (c) 2023, Andrew Kaster <akaster@serenityos.org>
 *
 * SPDX-License-Identifier: BSD-2-Clause
 */

package org.serenityos.ladybird

import android.content.Context
import android.content.Intent
import android.os.Message
import android.os.Process
import android.util.Log

/**
 * Hosts one WebContent instance. Each tab gets its own process: the UI binds one of the numbered
 * subclasses below (see WebContentSlots), each of which the manifest puts in a separate process.
 */
open class WebContentService : LadybirdServiceBase("WebContentService") {
    override fun handleServiceSpecificMessage(msg: Message): Boolean {
        return false
    }

    init {
        nativeInit();
    }

    override fun onCreate() {
        super.onCreate()
        // This runs in the tab's own process; the setting is read from the app's preferences file.
        nativeSetForceCpuPainting(BrowserStore.readCpuPainting(this))
    }

    private fun bindRequestServer(ipcFd: Int)
    {
        val connector = LadybirdServiceConnection(ipcFd, resourceDir)
        connector.onDisconnect = {
            // FIXME: Notify impl that service is dead and might need restarted
            Log.e(TAG, "RequestServer Died! :(")
        }
        // FIXME: Unbind this at some point maybe
        bindService(
            Intent(this, RequestServerService::class.java),
            connector,
            Context.BIND_AUTO_CREATE
        )
    }

    private fun bindImageDecoder(ipcFd: Int)
    {
        val connector = LadybirdServiceConnection(ipcFd, resourceDir)
        connector.onDisconnect = {
            // FIXME: Notify impl that service is dead and might need restarted
            Log.e(TAG, "ImageDecoder Died! :(")
        }
        // FIXME: Unbind this at some point maybe
        bindService(
            Intent(this, ImageDecoderService::class.java),
            connector,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // WebContent's global state can't be torn down and set up again within one process,
        // so a slot's process must not be reused for another tab. End it once the tab is gone.
        Log.i(TAG, "Last client unbound, exiting WebContent process")
        Process.killProcess(Process.myPid())
        return false
    }

    external fun nativeInit()
    private external fun nativeSetForceCpuPainting(forceCpuPainting: Boolean)

    companion object {
        init {
            System.loadLibrary("webcontentservice")
        }
    }
}

class WebContentService0 : WebContentService()
class WebContentService1 : WebContentService()
class WebContentService2 : WebContentService()
class WebContentService3 : WebContentService()
class WebContentService4 : WebContentService()
class WebContentService5 : WebContentService()
