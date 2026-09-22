# ECHO

An offline audiobook player for Android that never loses your place.

Point it at a folder of audio files and it becomes a series you can listen to. One screen, one
button, and a resume point that survives reboots.

<p align="center">
  <img src="docs/deck.png" width="30%" alt="A series shown as a disc, with the tick ring as the progress read-out" />
  <img src="docs/scrub-armed.png" width="30%" alt="The ring armed for scrubbing, with the handle visible" />
  <img src="docs/empty.png" width="30%" alt="Empty state prompting you to choose a folder" />
</p>

## Why

Most audiobook apps want your account, your library, and a network connection. This one wants a
folder. Playback never touches the network. The only networking is optional: reading Google Drive
catalogs someone has shared with you, and downloading the books you pick from them.

The thing it takes seriously is the resume point. Losing your place in a 30-hour book is the one
failure that actually matters, so the position is written continuously and the app rewinds slightly
when you come back.

## Features

**Playback**

- **Folders become series.** Every audio file inside becomes a chapter, sorted by file name and
  numerically aware, so `Chapter 2` sorts before `Chapter 10`. Nested folders are included.
- **New files are picked up.** Add files to a series folder and they appear the next time you open
  the app. It only ever adds: if files seem to be missing, the series is left untouched.
- **Rewinds by how long you were away.** 5 seconds after a short pause, 15 seconds after up to two
  hours, 30 seconds after up to two days, 45 seconds after longer. Deliberate moves — tapping a
  chapter, scrubbing the ring, a bookmark — are honoured exactly.
- **Survives everything.** Position is saved on a 5 second heartbeat while playing, and again on
  every pause, seek, chapter change, app swipe-away and service teardown.
- **Background playback** via a `MediaSessionService`, with notification and lock-screen controls,
  audio focus and headphone handling.
- **Even out volume** (Android 9+): an optional compressor and limiter for talks recorded unevenly.
- Playback speed 0.75×–2× per series, and a sleep timer (countdown or end-of-chapter).

**Knowing where you are**

- **Knows what you actually heard.** Each chapter shows as unheard, partly heard or finished. A
  chapter is only marked finished when it plays to its end, so a skipped one never looks done.
- **Every chapter remembers where you left it.** Go back to a half-heard chapter and it offers to
  resume there.
- **Hard to lose your place by accident.** Track rows need a second tap to play, every chapter jump
  offers "Go back", and headset, Bluetooth and notification controls only play and pause — they
  cannot skip chapters.
- **Bookmarks with notes.** Save a moment, write why, and jump back to it later.

**Your library**

- **Your own cover art and names.** Pick a picture from the gallery; it becomes the record label on
  the disc and the artwork on every chapter, including the lock screen.
- **Tidy chapter names**, per series and display only. `OSHO-Maha_Geeta_20` can read as
  `Maha Geeta · 20`; the files and stored names never change.
- **Backup and restore.** One file holds places, done marks, names, covers, bookmarks and the
  listening log. On a new phone, each series asks for its folder and everything is put back on it.
- **Google Drive catalogs.** Paste the link to a shared Drive folder and every book in it is listed.
  Download the ones you want; they play offline like any other series. See
  [Google Drive catalogs](#google-drive-catalogs).
- **Home-screen widget.** The last series and one button to carry on, without opening the app.
- **Real stream details** under the disc — codec, sample rate, bitrate, channels — read from the
  file. Bit depth appears only for lossless audio; nothing is guessed.
- Follows the system light/dark theme.

**Listening stats**

Tap the bar-chart button at the top. Everything comes from a log of real listening; nothing is
estimated backwards, so the screen shows the date counting began.

- Time listened today, this week, this month and all time
- The last 30 days as a bar chart, and your current and longest streak (a day counts after 5 minutes)
- Morning, afternoon, evening and night
- Sessions: how many, average and longest
- Chapters that played through to the end, per week
- Per series: first logged, days listened, time in, chapters done, and a finish date at your pace
  over the last two weeks

## Google Drive catalogs

A catalog is a Google Drive folder shared as **Anyone with the link**. Each folder inside it is a
book; sub-folders such as `CD 1` and `CD 2` are part of that book. A Drive shortcut to a folder
elsewhere works too, and a link to a folder that holds audio files directly is a single book.

```
My catalog/
  Book one/
    01.mp3
    02.mp3
    cover.jpg        optional; shown in the list and on the disc
  Book two/          or a shortcut to a folder somewhere else in Drive
```

In ECHO: `+` → **From Google Drive** → paste the link. Downloads go through Android's download
manager: they show in the notification bar, can wait for Wi-Fi, and carry on with the app closed.
When a book's last file lands it joins the library. **Get new** fetches chapters added to the Drive
folder later.

ECHO ships with no catalogs, and nothing is shared unless someone shares a link. Only put books in
a catalog that you have the right to share.

### Drive API key

Google requires an API key even for public folders. A build without one shows the Drive screen but
cannot read catalogs.

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project and enable
   the **Google Drive API**.
2. **Credentials → Create credentials → API key.**
3. Restrict it. API restrictions: Google Drive API. Application restrictions: Android apps, with
   package `com.echo.player` (and `com.echo.player.debug` for debug builds) and the SHA-1 of the
   certificate you sign with.
4. Add `echo.driveApiKey=YOUR_KEY` to `local.properties`, which is never committed.

## The interface

Each series is a disc, and the disc you are on names itself at the top of the screen. Swipe
vertically to move between series.

The tick ring around the disc is both the progress read-out and the scrubber — **but it will not
move until you tap it.** A tap arms it and shows the accent handle; it disarms itself after a few
seconds. Losing your place because a thumb brushed the edge is worse than one extra tap.

`−10s` and `+15s` sit either side of the cover. There are no next/previous chapter buttons; the
track list behind the `•••` does that job, along with bookmarks, speed, names and the cover. One
accent button at the bottom plays and pauses. The `+` button adds series and holds the volume
switch, backup and restore.

## Install

Download an APK from [Releases](../../releases), or build it yourself.

**Updates install themselves** from this repository's releases. The first time an update is
available, ECHO asks you to allow it to install apps; that is a one-time Android switch. After that,
on Android 12 and newer, a new version downloads and installs by itself the next time you leave the
app with nothing playing, and the next launch says "Updated". On Android 8–11, or wherever Android
still wants a say, it takes one tap. The status is under `+` → Updates.

Android only accepts an update signed with the same key as the installed app, so an APK from
anywhere else cannot replace yours this way. Make a backup before switching between builds from
different sources.

## Build

Requirements:

- **JDK 17 or newer.** The Android Gradle Plugin will not run on Java 8.
- Android SDK with platform **API 36**.
- A device or emulator running **Android 8.0 (API 26)** or newer.

```bash
git clone https://github.com/tejsav/echo-audiobook-player.git
cd echo-audiobook-player
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Install it with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Run the unit tests (plain JVM, no device needed):

```bash
./gradlew testReleaseUnitTest
```

Create `local.properties` with your SDK location if Gradle cannot find it:

```properties
sdk.dir=/path/to/Android/Sdk
```

On Windows use `gradlew.bat` in place of `./gradlew`. If Gradle picks the wrong JVM, pass
`-Dorg.gradle.java.home="/path/to/jdk-17"`.

## How it is put together

Kotlin, Jetpack Compose, Media3/ExoPlayer, Room. No dependency injection framework and no
navigation library.

| Layer | Where | Notes |
| --- | --- | --- |
| Playback | `playback/PlaybackService.kt` | `MediaSessionService` + ExoPlayer; the only writer of resume positions and the listening log; time-away rewind; resume from outside the app; volume levelling |
| Session bridge | `playback/PlayerConnection.kt` | `MediaController` mirrored into a `StateFlow` |
| Storage | `data/` | Room: `books`, `chapters`, `listening_sessions`, `bookmarks`; schemas exported to `app/schemas` |
| Import | `data/BookImporter.kt` | Storage Access Framework tree walk, tag reading, natural-order sort |
| Backup | `data/Backup.kt` | Plain JSON; restore matches chapters by file, then by name and order |
| Covers | `data/CoverStore.kt` | Gallery pictures are copied into app storage and downscaled, never referenced by uri |
| Stats | `stats/ListeningStats.kt` | Pure functions over the log, unit tested |
| Dial | `ui/dial/` | The disc, its tick ring and scrub gesture, and the accent button |
| Screens | `ui/deck/`, `ui/stats/` | The carousel and its sheets; the stats screen |
| Widget | `widget/ResumeWidget.kt` | `RemoteViews`; its button sends a media key |
| Drive | `drive/`, `ui/library/` | Catalogs read through the Drive API with an API key; downloads through `DownloadManager` into app storage; state read back from disk |

Things worth knowing before changing the playback code:

- Media items carry `bookId|chapterIndex` as their media id, so the service can always work out
  what to save without holding separate state that could drift.
- Loading a queue reports its start position through the same callbacks a real move does.
  `PlaybackService` suppresses saves for a moment after a playlist change; without that, the
  pick-up rewind would be saved back and walk the book backwards on every open.
- Resuming after a pause is rewound in one place, `onPlayWhenReadyChanged` in the service, so the
  app, the notification, a headset and the widget all behave the same. Any seek clears it.
- `books.currentChapterTitle` and `books.currentChapterDurationMs` are denormalised so the carousel
  can draw any disc from a single row. They are kept in step by the same UPDATE that writes the
  resume point.
- Bookmarks have no foreign key to books on purpose: re-importing replaces the book row, and a
  cascade would delete them.
- The scrub ring only claims touches in the outer band, and only once armed, so a vertical swipe
  through the middle of the disc still pages to the next series.

## Known limits

- Listening stats start from the version that added the log (1.2). Earlier listening was never
  recorded and is not reconstructed.
- A chapter's heard mark is a high-water mark, so playing on after a forward scrub counts the
  skipped stretch as heard.
- Volume levelling uses fixed settings and needs Android 9 or newer.
- Drive catalogs skip shortcuts to single files, and every copy of a build shares its API key's
  daily quota. Downloaded books live in app storage and are deleted with the app.
- Covers are stored at up to 1024px on the long edge, re-encoded as JPEG.
- Importing a large series reads tags from every file, which takes a moment. There is a progress
  panel and it can be cancelled.
- Screenshots above show the light theme.

## Contributing

Issues and pull requests are welcome. Pure logic has JVM unit tests under `app/src/test`. If you
change playback or the resume logic, please describe how you verified it on a device — process kill
and reboot are the cases that matter.

## Licence

[MIT](LICENSE).
