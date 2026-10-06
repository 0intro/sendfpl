# Intentionally empty, and that is a finding rather than an omission: R8 is on and no keep rule
# has been needed.
#
# The reason to bother is size. Unminified this app is a 12.7 MB APK and a 12.3 MB bundle, with
# 45 MB of uncompressed dex, almost all of it material-icons-extended carrying around two thousand
# icon builders for the seven that are drawn. Minified it is 1.4 MB and 3.6 MB, with 2.6 MB of dex.
#
# The one reflective call in the app is `BluetoothDevice.isConnected()`, in bt/GarminDevices.kt.
# It resolves a framework method by name, and R8 does not rewrite the framework, so nothing here
# has to protect it. It is wrapped in runCatching regardless, so a failure dims a row rather than
# crashing.
#
# What was verified on a handset against a minified release build:
#
#   - it installs and launches with no crash;
#   - it adopts the credential bundled in its assets, which exercises org.json, the Android
#     Keystore and AES-GCM through CredentialStore;
#   - it enumerates paired devices, which exercises the reflective isConnected() above;
#   - it parses a typed route and renders the ARINC 702A string;
#   - a send fails at Connecting with the right message, and logcat holds no ClassNotFound and no
#     NoSuchMethod from app.sendfpl.
#
# And on an emulator, API 35, which reaches everything above except the radio:
#
#   - a route file opens and imports, giving the same route string as the JVM tests;
#   - a refusal renders from string resources through the sealed Problem hierarchy, which is the
#     shape R8 is most likely to have something to say about.
#
# What has **not** been run minified: an RFCOMM session past connect, because that needs a powered
# navigator. Nothing in the code above suggests a problem there, since the whole protocol layer is
# plain Kotlin with no reflection and no serialisation, but it has not been flown. One bench upload
# closes it.
