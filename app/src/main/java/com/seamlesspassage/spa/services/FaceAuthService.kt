package com.seamlesspassage.spa.services

import kotlinx.coroutines.delay

sealed class FaceAuthResult {
    data class Success(val userId: String) : FaceAuthResult()
    object Failure : FaceAuthResult()
}

class FaceAuthService {
    // Placeholder: simulate face auth processing
    suspend fun authenticate(faceId: String): FaceAuthResult {
        delay(700) // simulate processing
        // 80% success chance
        return if ((0..9).random() < 8) FaceAuthResult.Success(userId = "U123456") else FaceAuthResult.Failure
    }
}
