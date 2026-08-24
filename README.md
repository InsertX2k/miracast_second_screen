# Miracast/Wi-Fi Display Second Screen App
Miracast/Wi-Fi Display Sink implementation from scratch for Android devices. [Requires Root]

## Components
[The sink app](app/) - a fully functional, multi-threaded, high-performance miracast sink app.

[The Magisk module](module/) - a Magisk module for rooted devices that allows the app to gain root privileges and be installed as a privileged system app (REQUIRED).

[Wi-Fi Direct/Wi-Fi P2P Primary Device Type Xposed Hook](xposed_hook/) - An Xposed/LSPosed hook that hooks into `WifiP2pNative`'s initialization methods to make your Android phone reported as a Smart TV to nearby P2P Peers (Very useful, You will need this when connecting to Windows Sources for example).

## What works
* Fully working WFD RTSP messages handling mechanism.
* Smooth and stable video playback **(display modes up to 1920x1080 @ 60 Hz!)**
* Sends RTCP receiver reports for adaptive bandwidth modulation.
* Contains a real display EDID (taken from a real smart TV but slightly modified)
* Works and tested on both Windows and Android (Samsung Smart View) Sources.

## Plans for improvement (will be applied in no order)
* Implement audio stream playback (using LPCM, no decoding needed, plus supported by nearly all Miracast capable sources)
* Implement UIBC (User Input Back Channel); Just touch that registers properly on the source.
* Add support for `M15` (`wfd_standby`) RTSP Messages
* Fix UI bugs (when source disconnects during an active session the app just freezes).