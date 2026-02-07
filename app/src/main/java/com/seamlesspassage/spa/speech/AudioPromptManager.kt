package com.seamlesspassage.spa.speech

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes
import com.seamlesspassage.spa.R

/**
 * 语音提示管理：不再依赖系统 TTS，改为播放预录音频。
 *
 * 调用方仍然使用 speak(text)，内部根据固定文案映射到 res/raw 下的音频文件。
 */
class AudioPromptManager(context: Context) {
    private val appContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null

    /**
     * 将界面文案映射到对应的原始音频资源。
     * 请在 res/raw 下放入对应文件（例如 .mp3），并保持这些资源 ID 存在。
     */
    @RawRes
    private fun resolvePromptResId(text: String): Int? = when (text) {
        // Idle 提示：请正对摄像头，您无须操作，等待完成识别
        "请正对摄像头，您无须操作，等待完成识别" -> R.raw.prompt_idle

        // 正在认证身份
        "正在认证身份，请保持正对镜头" -> R.raw.prompt_verifying

        // 认证成功
        "认证成功，请通过" -> R.raw.prompt_success

        // 认证失败
        "认证失败" -> R.raw.prompt_failed

        // 借书成功
        "借书成功" -> R.raw.prompt_borrow_success

        // 借书失败
        "借书失败" -> R.raw.prompt_borrow_failed

        // 系统错误
        "系统错误，请稍后重试" -> R.raw.prompt_error

        else -> null
    }

    fun speak(text: String) {
        val resId = resolvePromptResId(text)
        if (resId == null) {
            Log.w(TAG, "No audio resource mapped for text: $text")
            return
        }

        try {
            // 停止并释放上一次的播放实例
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null

            mediaPlayer = MediaPlayer.create(appContext, resId)
            mediaPlayer?.setOnCompletionListener { mp ->
                mp.release()
                if (mediaPlayer === mp) {
                    mediaPlayer = null
                }
            }

            Log.d(TAG, "Play prompt audio for text: $text, resId=$resId")
            mediaPlayer?.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play prompt audio for text: $text", e)
        }
    }

    fun shutdown() {
        Log.d(TAG, "shutdown audio prompt manager")
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "AudioPromptManager"
    }
}
