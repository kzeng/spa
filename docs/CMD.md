## TARGET
- Android Studio 2025.2
- JDK 17 (required by AGP 8.2)
- Kotlin 1.9.22
- Material 3 (Jetpack Compose)
- Minimum SDK 23; Target SDK 33 (Android 13)



## Build
ANDROID_SDK_ROOT="$HOME/Android/Sdk" "$HOME/.gradle/wrapper/dists/gradle-8.13-bin/ap7pdhvhnjtc6mxtzz89gkh0c/gradle-8.13/bin/gradle" --no-daemon assembleDebug


## Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk


WiFi debug
-----------------

adb kill-server
adb start-server
adb devices

adb connect 192.168.0.101:5555  #这里的IP 使用你安卓设备的实际IP地址
adb devices       # 确认有 192.168.0.101:5555 device


already connected to 192.168.0.101:5555
List of devices attached
192.168.0.101:5555      device


## TTS Speak
https://ttsfree.com/#try-now  
