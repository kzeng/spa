package com.seamlesspassage.spa.services

import kotlinx.coroutines.delay

sealed class Sip2Result {
    data class Allowed(val reason: String = "权限检査通过") : Sip2Result()
    data class Denied(val reason: String) : Sip2Result()
}

class Sip2Service {
    // Placeholder SIP2 check
    suspend fun check(userId: String): Sip2Result {
        delay(500)
        return if ((0..9).random() < 9) Sip2Result.Allowed() else Sip2Result.Denied("权限不足或借阅异常")
    }
}
