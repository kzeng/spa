package com.seamlesspassage.spa.services

import kotlinx.coroutines.delay

sealed class GateResult {
    object Opened : GateResult()
    data class Failed(val message: String) : GateResult()
}

class GateService {
    suspend fun open(): GateResult {
        delay(300)
        return GateResult.Opened
    }
}
