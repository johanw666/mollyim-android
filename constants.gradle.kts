val signalBuildToolsVersion by extra("35.0.0")      // MOLLY: Dockerfile must match this version
val signalCompileSdkVersion by extra("android-35")  // MOLLY: Dockerfile must match this version
val signalTargetSdkVersion by extra(34)
val signalMinSdkVersion by extra(26) // JW
val signalNdkVersion by extra("27.0.12077973")
val signalJavaVersion by extra(JavaVersion.VERSION_17)
val signalKotlinJvmTarget by extra("17")
