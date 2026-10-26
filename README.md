# ReelGuard

Blocks Instagram Reels scrolling on Android, but still lets you watch a reel someone sends you.

- Open the **Reels tab** (or tap a reel in the feed/explore) → ReelGuard presses Back: *"Reels are off"*.
- Open a reel from a **DM** (or a reel link from WhatsApp etc.) → it plays normally.
- **Swipe** to the next reel → ReelGuard presses Back: *"That's the one you were sent"*.

It only reads Instagram's screen, does a quick check a few times a second while Instagram is open, and otherwise only notices which app is open. No internet permission, no account, nothing leaves the phone.

## Build it (pick one)

### A. Android Studio (on a computer)
1. Install [Android Studio](https://developer.android.com/studio).
2. *File → Open* → choose this `reelguard` folder. Let it sync (it downloads what it needs; accept any suggested Gradle/AGP update).
3. On your phone: *Settings → About phone →* tap *Build number* 7 times → *Developer options → USB debugging* on.
4. Plug the phone in, pick it at the top of Android Studio, press ▶ Run.

### B. GitHub (no Android Studio needed)
1. Create a new (private) repo on GitHub and upload this folder's contents.
2. Open the repo's **Actions** tab → **Build APK** → wait ~3 min.
3. Download the **ReelGuard-debug-apk** artifact (a zip with `app-debug.apk` inside) on your phone and install it. Android will ask to allow installs from your browser/files app.

## Turn it on (on the phone)
1. Open ReelGuard → **Turn on in Accessibility settings** → *ReelGuard* → on.
2. If Android says the setting is restricted (Android 13+ does this for apps not from the Play Store):
   ReelGuard → **Open App info** → ⋮ (top right) → **Allow restricted settings** → try step 1 again.
3. Recommended: App info → **Battery → Unrestricted**, so Samsung/Xiaomi etc. don't switch it off.

## When Instagram updates and something stops working
All of Instagram's screen IDs are in `app/src/main/java/com/reelguard/app/Rules.kt`.

1. ReelGuard → **Capture Instagram screen IDs**. Instagram opens with blocking paused.
2. Go to the screen that isn't handled right (e.g. the Reels tab, or a DM reel that gets blocked).
3. Go back to ReelGuard. The IDs of that screen are listed (you can select & copy them).
4. Add the relevant ID to the right list in `Rules.kt` and rebuild.

## How it decides
| Situation | What happens |
|---|---|
| Reels tab selected in the bottom bar | Blocked |
| Reels player opened from Home, Search/Explore or Profile | Blocked |
| Reels player opened while you were in a DM chat | Allowed (one reel) |
| Reel link opened from another app (WhatsApp, Messages…) | Allowed (one reel) |
| Swipe in an allowed reel moves to a different item | Back + "That's the one you were sent" |
| Scrolling comments under an allowed reel | Fine |
| Back didn't leave Reels after 3 tries | Stops trying, tells you to use Fix detection |

ReelGuard knows you came "from another app" because Android tells it which app is in front.
Outside Instagram it only listens for app switches (a handful of events a minute); the detailed
Instagram events are switched on only while Instagram is open.

## Project layout
```
app/src/main/java/com/reelguard/app/
  ReelGuardService.kt   the blocker (accessibility service)
  Rules.kt              Instagram view IDs – the only file you should need to edit
  Overlay.kt            the little message pill
  MainActivity.kt       settings screen
  Prefs.kt              on/off + counters
app/src/main/res/xml/reel_guard_service.xml   which app & events Android sends (Instagram only)
```
