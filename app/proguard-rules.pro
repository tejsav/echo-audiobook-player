# Media3 / ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.session.** { *; }

# Room generated code
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Our MediaSessionService is resolved by name from the manifest.
-keep class com.geeta.player.playback.PlaybackService { *; }
