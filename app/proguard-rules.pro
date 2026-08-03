# Generated AIDL stubs are instantiated across the Shizuku process boundary.
-keep class com.agentkosticka.amply.shizuku.protocol.IVolumeService$Stub { *; }
-keep class com.agentkosticka.amply.shizuku.protocol.IVolumeService$Stub$Proxy { *; }
-keep class com.agentkosticka.amply.shizuku.protocol.PlaybackSessionParcel { *; }
-keep class com.agentkosticka.amply.shizuku.protocol.OperationResultParcel { *; }

# Shizuku reflects the UserService entry point from the configured component.
-keep class com.agentkosticka.amply.shizuku.VolumeUserService { public <init>(); }
-keep class com.agentkosticka.amply.shizuku.server.VolumeUserService { public <init>(); }

# Hidden audio entry points are resolved by compatible method signature at runtime.
-keepclassmembers class android.media.AudioManager {
    *** getService(...);
}
