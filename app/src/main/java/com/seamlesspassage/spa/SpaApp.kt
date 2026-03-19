package com.seamlesspassage.spa

import android.app.Application
import android.util.Log
import com.seamlesspassage.spa.services.GateHttpServer
import com.seamlesspassage.spa.services.GateService
import java.io.IOException

/**
 * 自定义 Application，用于在进程启动时初始化并启动门禁 HTTP 服务。
 */
class SpaApp : Application() {

    private var gateHttpServer: GateHttpServer? = null

    override fun onCreate() {
        super.onCreate()

        // 使用与 UI 相同的 GateService（共用底层通道实现）
        val gateService = GateService()
        gateHttpServer = GateHttpServer(
            AppConfig.GATE_HTTP_PORT,
            gateService,
            AppConfig.GATE_HTTP_TOKEN
        )

        try {
            gateHttpServer?.start()
            Log.i("SpaApp", "Gate HTTP server started on port ${AppConfig.GATE_HTTP_PORT}")
        } catch (e: IOException) {
            Log.e("SpaApp", "Failed to start Gate HTTP server", e)
            gateHttpServer = null
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        gateHttpServer?.stop()
        gateHttpServer = null
    }
}
