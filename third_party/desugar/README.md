# Reproducible Java library backport inputs

Google Maven artifacts, pinned to 2.1.5:
- https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs_nio/2.1.5/desugar_jdk_libs_nio-2.1.5.jar
  SHA256 d8044befae095781b9a80bf1faa92edc30382d75d437476784c1bf991598a976
- https://dl.google.com/dl/android/maven2/com/android/tools/desugar_jdk_libs_configuration_nio/2.1.5/desugar_jdk_libs_configuration_nio-2.1.5.jar
  SHA256 7db51661cd07d1fd5cf12769a75ba201910624bb3801c09363dd3cb28e31c51b

`desugar.json` is extracted unchanged from configuration JAR `META-INF/desugar/d8/desugar.json`.
D8 rewrites call sites and L8 builds/shrinks the supplied backported implementations. The APK carries both.
Source and licensing: https://github.com/google/desugar_jdk_libs (GPL-2.0 with Classpath Exception; configuration Apache-2.0).
Notices are packaged in `app/src/main/assets/third_party/desugar`.
The old-system NIO implementation is NOT used to verify local file safety: AndroidFileAccess uses real O_NOFOLLOW and fstat instead.

AndroidX multidex 2.0.1 `classes.jar` is extracted unchanged to app/libs/multidex-2.0.1.jar from
https://dl.google.com/dl/android/maven2/androidx/multidex/multidex/2.0.1/multidex-2.0.1.aar
JAR SHA256 43d6755b439e7d94fea208cc6c5c87b349029b26076d708fadcae66d91a2c828
Apache-2.0; required for API19 multi-DEX loading, installed in Application.attachBaseContext.
