package com.isaivazhi.app

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import com.isaivazhi.app.engine.AppContainer

/**
 * Process-lifetime owner for engines and staged cold-start hydration.
 *
 * Startup database/vector work is intentionally centralized in
 * StartupCoordinator. Keeping it out of Application.onCreate prevents
 * duplicate stats, nearest-neighbor and full-heap jobs from competing with
 * the first UI render.
 */
class IsaiVazhiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        com.isaivazhi.app.engine.DebugLogCapture.installCrashHandler(this)
        container = AppContainer(this)
        if (!isDefaultProcess()) {
            android.util.Log.i(
                "IsaiVazhiApp",
                "Skipping main-process startup in ${currentProcessName()}",
            )
            return
        }
        container.recommendationRefresh.start()
        container.queueContinuation.start()
        container.startup.start()
    }

    private fun isDefaultProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName()
        }
        val pid = Process.myPid()
        val am = getSystemService(ActivityManager::class.java) ?: return packageName
        return am.runningAppProcesses
            ?.firstOrNull { it.pid == pid }
            ?.processName
            ?: packageName
    }
}
