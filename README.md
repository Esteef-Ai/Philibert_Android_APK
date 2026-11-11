# Philibert for Android

Philibert's chat on your phone, plus its hands on the phone. The assistant runs on your own
computer (its models, its GPU), and you reach it over Tailscale. When you ask for something to be
done on the phone ("set an alarm for 6:30", "text Mom I'm running late", "open Plex and play my
audiobook"), Philibert works the phone through this app.

## How it fits together

```
phone: Philibert app ──Tailscale──▶ computer: Philibert's phone address (port 11500, key in the address)
   WebView: the phone chat page            /phone/            the chat (the computer's own page)
   HandsService: long poll                 /phone/api/device_register | device_poll | device_result
   Actions + PhoneAccessibility            the device fabric in NeuralDock_launcher.py
```

The app only connects out. Nothing listens on the phone. The computer's relay answers only
devices on your tailnet, and only with the key that is part of the address.

## Build

The project needs Android Studio's JDK and the Android SDK, and nothing else (no libraries).

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It is signed with this
computer's debug key, so later builds from this computer install over earlier ones.

## Install and set up on the phone

1. Copy the APK to the phone and open it. Allow "install unknown apps" for whatever app you opened
   it from.
2. Open Philibert, paste the phone address from the computer's Philibert (where the phone chat
   is turned on), and tap **Connect**.
3. In **Setup**, turn on each item:
   - **Philibert hands** (Accessibility). Android blocks this at first for sideloaded apps. Go to
     Settings > Apps > Philibert > ⋮ > **Allow restricted settings**, then turn it on.
   - **Texts, calls, contacts, location**
   - **Read notifications**
   - **Stay connected** (battery: unrestricted)

## What it can do

`set_alarm`, `set_timer`, `open_app`, `list_apps`, `open_url` (links, maps, directions),
`send_text`, `call`, `find_contact`, `flashlight`, `volume`, `media`, `battery`, `location`,
`read_notifications`, `notify`, and screen control:

- `read_screen` (a numbered list of what's showing)
- `tap`, `long_press`, `type_text`, `scroll`, `swipe`
- `press` (back / home / recents / notifications / quick settings / lock screen / screenshot)
- `wait`

The full contract is in `Actions.kt`. The app sends it to Philibert when it pairs.

**Always asked on the phone first:**

- sending a text
- placing a call
- tapping a button that sends, pays, buys, deletes, posts, shares, transfers, confirms or calls

Banking apps and password screens hide themselves from accessibility. Philibert can't read or
use them.

## Testing without a phone

`tools/fake_phone.py` pairs as a phone and answers actions. It then runs one chat and prints
what the "phone" was asked to do:

```
python tools/fake_phone.py http://100.x.y.z:11500/k-.../phone/ "Set an alarm for 6:30"
```
