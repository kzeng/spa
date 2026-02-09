package com.seamlesspassage.spa

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateManager {

    private const val LATEST_RELEASE_API =
        "https://api.github.com/repos/kzeng/spa/releases/latest"

    suspend fun checkForUpdates(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        val currentVersion = getCurrentVersion(context)

        val latestJson = fetchLatestReleaseJson() ?: return@withContext UpdateResult.Error("无法获取版本信息")
        val latestVersion = latestJson.optString("tag_name")
        if (latestVersion.isNullOrBlank()) {
            return@withContext UpdateResult.Error("版本号为空")
        }

        if (!isNewerVersion(latestVersion, currentVersion)) {
            return@withContext UpdateResult.UpToDate
        }

        val assets = latestJson.optJSONArray("assets") ?: return@withContext UpdateResult.Error("未找到可下载的安装包")
        if (assets.length() == 0) return@withContext UpdateResult.Error("未找到可下载的安装包")

        // 过滤出 .apk 文件
        val apkAssets = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .filter { it.optString("name", "").endsWith(".apk", ignoreCase = true) }
        
        if (apkAssets.isEmpty()) {
            return@withContext UpdateResult.Error("未找到APK安装包")
        }

        // 取第一个 .apk 文件
        val apkAsset = apkAssets.first()
        val apkUrl = apkAsset.optString("browser_download_url")
        if (apkUrl.isBlank()) return@withContext UpdateResult.Error("安装包下载地址为空")

        UpdateResult.UpdateAvailable(latestVersion, apkUrl)
    }

    suspend fun downloadAndInstall(context: Context, apkUrl: String, onProgress: ((Int) -> Unit)? = null): UpdateResult = withContext(Dispatchers.IO) {
        android.util.Log.d("UpdateManager", "开始下载: $apkUrl")
        val downloadResult = downloadApk(context, apkUrl, onProgress)
        if (downloadResult is DownloadResult.Failure) {
            return@withContext UpdateResult.Error("下载失败: ${downloadResult.reason}")
        }
        val apkFile = (downloadResult as DownloadResult.Success).file
        android.util.Log.d("UpdateManager", "下载完成: ${apkFile.absolutePath}, 大小: ${apkFile.length()} bytes")
        
        // 返回APK路径信息
        val apkPath = apkFile.absolutePath
        val apkSize = apkFile.length()
        
        val installResult = installApk(context, apkFile)
        android.util.Log.d("UpdateManager", "安装结果: $installResult")
        if (installResult is InstallResult.Success) {
            UpdateResult.UpdatedWithPath(apkPath, apkSize)
        } else {
            UpdateResult.ErrorWithPath("安装失败: ${(installResult as InstallResult.Failure).reason}", apkPath, apkSize)
        }
    }

    private fun getCurrentVersion(context: Context): String {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName ?: "0.0.0"
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
            }
        } catch (_: Exception) {
            "0.0.0"
        }
    }

    private fun fetchLatestReleaseJson(): JSONObject? {
        return try {
            val url = URL(LATEST_RELEASE_API)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            conn.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                JSONObject(body)
            }
        } catch (_: Exception) {
            null
        }
    }

    // 简单语义版本比较：v1.2.3 形式，忽略前缀 v/V
    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parse(v: String) = v.trim().trimStart('v', 'V')
            .split(".")
            .mapNotNull { it.toIntOrNull() }

        val l = parse(latest)
        val c = parse(current)
        val max = maxOf(l.size, c.size)
        for (i in 0 until max) {
            val li = l.getOrNull(i) ?: 0
            val ci = c.getOrNull(i) ?: 0
            if (li > ci) return true
            if (li < ci) return false
        }
        return false
    }

    private fun getDownloadFile(context: Context): File {
        // 使用 cacheDir 保存更新文件，避免存储权限问题
        return File(context.cacheDir, "spa-update-latest.apk")
    }

    private fun downloadApk(context: Context, urlStr: String, onProgress: ((Int) -> Unit)? = null): DownloadResult {
        val maxRetries = 5
        var retryCount = 0
        var lastException: Exception? = null
        
        val apkFile = getDownloadFile(context)
        // Ensure parent directory exists
        apkFile.parentFile?.mkdirs()
        
        var existingSize = if (apkFile.exists()) apkFile.length() else 0L
        android.util.Log.d("UpdateManager", "下载目标路径: ${apkFile.absolutePath}, 已存在大小: $existingSize bytes")
        
        while (retryCount < maxRetries) {
            try {
                android.util.Log.d("UpdateManager", "准备下载: $urlStr (尝试 ${retryCount + 1}/$maxRetries), 已下载: $existingSize bytes")
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 45_000
                conn.readTimeout = 300_000

                // 添加请求头，模拟浏览器请求
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36")
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                
                // 如果文件已存在部分内容，尝试断点续传
                if (existingSize > 0) {
                    conn.setRequestProperty("Range", "bytes=$existingSize-")
                    android.util.Log.d("UpdateManager", "尝试断点续传，从字节 $existingSize 开始")
                }
                
                val responseCode = conn.responseCode
                android.util.Log.d("UpdateManager", "HTTP响应码: $responseCode")
                
                // 检查响应码：206表示部分内容（断点续传），200表示完整内容
                val isPartialContent = responseCode == HttpURLConnection.HTTP_PARTIAL
                val isSuccess = responseCode == HttpURLConnection.HTTP_OK || isPartialContent
                
                if (!isSuccess) {
                    val errorMsg = "HTTP错误: $responseCode"
                    android.util.Log.e("UpdateManager", "下载失败，响应码: $responseCode")
                    // 如果是服务器错误，可以重试
                    if (responseCode in 500..599) {
                        retryCount++
                        Thread.sleep(2000L * retryCount)
                        continue
                    }
                    return DownloadResult.Failure(errorMsg)
                }

                // 获取内容长度
                val contentRangeHeader = conn.getHeaderField("Content-Range")
                var contentLength = conn.contentLengthLong
                var totalFileSize = contentLength
                
                // 解析Content-Range头获取文件总大小（对于断点续传）
                if (contentRangeHeader != null && contentRangeHeader.contains("/")) {
                    val totalSizeStr = contentRangeHeader.substringAfterLast("/")
                    totalFileSize = totalSizeStr.toLongOrNull() ?: contentLength
                    android.util.Log.d("UpdateManager", "从Content-Range解析总大小: $totalFileSize")
                }
                
                if (isPartialContent) {
                    android.util.Log.d("UpdateManager", "服务器支持断点续传，从 $existingSize 字节继续下载")
                    contentLength = totalFileSize - existingSize
                } else {
                    android.util.Log.d("UpdateManager", "服务器返回完整文件，重新开始下载")
                    // 如果不是部分内容，删除现有文件重新开始
                    if (apkFile.exists()) {
                        apkFile.delete()
                        existingSize = 0
                    }
                    totalFileSize = contentLength
                }
                
                android.util.Log.d("UpdateManager", "内容长度: $contentLength, 文件总大小: $totalFileSize, 已下载: $existingSize")
                
                android.util.Log.d("UpdateManager", "开始写入文件: ${apkFile.absolutePath}")
                
                var totalBytesRead = existingSize
                var lastReportedProgress = -1
                val buffer = ByteArray(16384)
                
                conn.inputStream.use { input ->
                    val out = if (existingSize > 0 && isPartialContent) {
                        // 追加模式
                        FileOutputStream(apkFile, true)
                    } else {
                        // 新建或覆盖模式
                        FileOutputStream(apkFile)
                    }
                    
                    out.use { outStream ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            if (totalFileSize > 0) {
                                val progress = (totalBytesRead * 100 / totalFileSize).toInt()
                                // 报告进度
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    onProgress?.invoke(progress)
                                    if (progress % 10 == 0 || progress == 100) {
                                        android.util.Log.d("UpdateManager", "下载进度: $progress% ($totalBytesRead/$totalFileSize)")
                                    }
                                }
                            }
                        }
                    }
                }
                android.util.Log.d("UpdateManager", "文件写入完成，大小: ${apkFile.length()} bytes, 读取: $totalBytesRead bytes")
                
                // 验证文件大小
                if (apkFile.length() == 0L) {
                    val errorMsg = "下载的文件为空"
                    android.util.Log.e("UpdateManager", errorMsg)
                    return DownloadResult.Failure(errorMsg)
                }
                
                // 验证文件大小是否与总大小匹配
                if (totalFileSize > 0 && apkFile.length() != totalFileSize) {
                    android.util.Log.w("UpdateManager", "文件大小不匹配: 期望 $totalFileSize, 实际 ${apkFile.length()}")
                    // 如果大小不匹配但文件不为空，仍然尝试安装
                    if (apkFile.length() > totalFileSize * 0.9) {
                        android.util.Log.d("UpdateManager", "文件大小接近，继续安装")
                    } else {
                        val errorMsg = "文件大小不完整: ${apkFile.length()}/$totalFileSize"
                        android.util.Log.e("UpdateManager", errorMsg)
                        return DownloadResult.Failure(errorMsg)
                    }
                }
                
                return DownloadResult.Success(apkFile)
            } catch (e: Exception) {
                lastException = e
                val errorMsg = "下载异常 (尝试 ${retryCount + 1}/$maxRetries): ${e.message}"
                android.util.Log.e("UpdateManager", errorMsg, e)
                
                // 更新现有文件大小，用于下次重试
                existingSize = if (apkFile.exists()) apkFile.length() else 0L
                
                // 检查是否是网络错误，可以重试
                val shouldRetry = when {
                    e is java.net.ProtocolException && e.message?.contains("unexpected end of stream") == true -> true
                    e is java.net.SocketTimeoutException -> true
                    e is java.net.ConnectException -> true
                    e is java.net.SocketException && e.message?.contains("Socket closed") == true -> true
                    e is java.io.InterruptedIOException -> true
                    else -> false
                }
                
                if (shouldRetry) {
                    retryCount++
                    android.util.Log.d("UpdateManager", "网络异常，准备重试 (${retryCount}/$maxRetries)，已下载: $existingSize bytes")
                    // 指数退避
                    val waitTime = 2000L * retryCount * retryCount
                    android.util.Log.d("UpdateManager", "等待 ${waitTime}ms 后重试")
                    Thread.sleep(waitTime)
                    continue
                } else {
                    // 其他异常，不重试
                    break
                }
            }
        }
        
        val finalErrorMsg = "下载失败，已重试 $maxRetries 次: ${lastException?.message ?: "未知错误"}"
        android.util.Log.e("UpdateManager", finalErrorMsg)
        return DownloadResult.Failure(finalErrorMsg)
    }

    private fun installApk(context: Context, apkFile: File): InstallResult {
        android.util.Log.d("UpdateManager", "开始安装APK: ${apkFile.absolutePath}, 大小: ${apkFile.length()} bytes")
        
        // 检查文件是否存在
        if (!apkFile.exists()) {
            android.util.Log.e("UpdateManager", "APK文件不存在: ${apkFile.absolutePath}")
            return InstallResult.Failure("APK文件不存在")
        }
        
        // 检查文件大小
        if (apkFile.length() == 0L) {
            android.util.Log.e("UpdateManager", "APK文件为空")
            return InstallResult.Failure("APK文件为空")
        }
        
        // 检查Android版本和安装权限
        var needsInstallPermission = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val packageManager = context.packageManager
            if (!packageManager.canRequestPackageInstalls()) {
                android.util.Log.w("UpdateManager", "没有安装未知来源应用的权限，需要用户授权")
                needsInstallPermission = true
                // 我们仍然尝试安装，如果失败会返回明确的错误信息
            }
        }
        
        val uri: Uri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val authority = "${context.packageName}.fileprovider"
                android.util.Log.d("UpdateManager", "使用FileProvider, authority: $authority")
                FileProvider.getUriForFile(context, authority, apkFile)
            } else {
                android.util.Log.d("UpdateManager", "使用Uri.fromFile (API < 24)")
                Uri.fromFile(apkFile)
            }
        } catch (e: Exception) {
            android.util.Log.e("UpdateManager", "创建URI失败", e)
            return InstallResult.Failure("创建URI失败: ${e.message}")
        }

        android.util.Log.d("UpdateManager", "APK URI: $uri")
        
        // 如果缺少安装权限，在错误信息中提示用户
        val permissionHint = if (needsInstallPermission) {
            " (可能需要先启用'安装未知应用'权限)"
        } else ""
        
        // 创建安装Intent - 使用更兼容的方式
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setData(uri)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 设置额外的Intent参数以提高兼容性
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            // 对于某些设备，需要明确设置MIME类型
            setType("application/vnd.android.package-archive")
        }

        android.util.Log.d("UpdateManager", "安装Intent: $intent")

        // 检查是否有活动可以处理这个Intent
        val packageManager = context.packageManager
        val activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        android.util.Log.d("UpdateManager", "可以处理安装Intent的活动数量: ${activities.size}")

        if (activities.isEmpty()) {
            android.util.Log.e("UpdateManager", "没有找到可以处理INSTALL_PACKAGE Intent的活动，尝试使用VIEW Intent")
            
            // 回退到传统的VIEW Intent
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            
            val fallbackActivities = packageManager.queryIntentActivities(fallbackIntent, PackageManager.MATCH_DEFAULT_ONLY)
            android.util.Log.d("UpdateManager", "可以处理VIEW Intent的活动数量: ${fallbackActivities.size}")
            
            if (fallbackActivities.isEmpty()) {
                android.util.Log.e("UpdateManager", "没有找到可以处理任何安装Intent的活动")
                return InstallResult.Failure("没有找到安装程序，请检查设备设置$permissionHint")
            }
            
            // 使用VIEW Intent
            return try {
                android.util.Log.d("UpdateManager", "正在使用VIEW Intent启动安装活动...")
                context.startActivity(fallbackIntent)
                android.util.Log.d("UpdateManager", "安装活动已启动（VIEW Intent）")
                InstallResult.Success
            } catch (e: Exception) {
                android.util.Log.e("UpdateManager", "使用VIEW Intent启动安装失败", e)
                InstallResult.Failure("启动安装失败: ${e.message}$permissionHint")
            }
        }

        return try {
            android.util.Log.d("UpdateManager", "正在启动安装活动...")
            context.startActivity(intent)
            android.util.Log.d("UpdateManager", "安装活动已启动")
            InstallResult.Success
        } catch (e: ActivityNotFoundException) {
            android.util.Log.e("UpdateManager", "未找到安装程序", e)
            InstallResult.Failure("未找到安装程序: ${e.message}$permissionHint")
        } catch (e: SecurityException) {
            android.util.Log.e("UpdateManager", "权限不足", e)
            InstallResult.Failure("权限不足: ${e.message}$permissionHint")
        } catch (e: Exception) {
            android.util.Log.e("UpdateManager", "启动安装失败", e)
            InstallResult.Failure("启动安装失败: ${e.message}$permissionHint")
        }
    }
}

sealed class UpdateResult {
    data object UpToDate : UpdateResult()
    data class UpdateAvailable(val newVersion: String, val downloadUrl: String) : UpdateResult()
    data object Updated : UpdateResult()
    data class UpdatedWithPath(val apkPath: String, val apkSize: Long) : UpdateResult()
    data class Error(val reason: String) : UpdateResult()
    data class ErrorWithPath(val reason: String, val apkPath: String, val apkSize: Long) : UpdateResult()
}

private sealed class DownloadResult {
    data class Success(val file: File) : DownloadResult()
    data class Failure(val reason: String) : DownloadResult()
}

private sealed class InstallResult {
    data object Success : InstallResult()
    data class Failure(val reason: String) : InstallResult()
}
