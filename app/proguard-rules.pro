# 本项目 release 构建暂未启用混淆（isMinifyEnabled = false）。
# 若未来开启混淆，需保留：
#  - org.apache.commons.compress（反射读取 tar 头）
#  - org.tukaani.xz
#  - WebView JS 桥（@JavascriptInterface）
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keepclassmembers class * {
  @android.webkit.JavascriptInterface <methods>;
}
