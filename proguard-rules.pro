# Coil, OkHttp, Leanback and coroutines ship their own consumer rules.
# OkHttp's optional TLS providers are not on Android:
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
