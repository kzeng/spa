package com.seamlesspassage.spa.services

import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking

/**
 * 内嵌 HTTP 服务，暴露 /openAllDoors 接口，供进馆平板远程调用。
 *
 * - 仅在局域网内部使用；
 * - 通过简单的共享 Token 做鉴权；
 * - 内部直接复用 GateService（以及底层 QbChannelRfidChannelService）。
 */
class GateHttpServer(
    port: Int,
    private val gateService: GateService,
    private val token: String
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            handleRequest(session)
        } catch (e: Exception) {
            val errorMessage = e.message ?: "unknown"
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success":false,"message":"internal error: $errorMessage"}"""
            )
        }
    }

    private fun handleRequest(session: IHTTPSession): Response {
        if (session.uri != "/openAllDoors" || session.method != Method.POST) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain",
                "Not Found"
            )
        }

        val headerToken = session.headers["x-spa-token"] ?: ""
        if (token.isNotEmpty() && headerToken != token) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                """{"success":false,"message":"unauthorized"}"""
            )
        }

        val result = runBlocking {
            gateService.openAllDoorsForReturn()
        }

        return when (result) {
            is GateResult.Opened -> newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                """{"success":true,"message":"doors opened"}"""
            )

            is GateResult.Failed -> newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success":false,"message":"${result.message}"}"""
            )
        }
    }
}
