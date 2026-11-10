# Empty on purpose: R8 is on and needs no keep rule. The protocol layer is plain Kotlin with no
# reflection and no serialisation. The one reflective call, BluetoothDevice.isConnected() in
# bt/GarminDevices.kt, resolves a framework method, which R8 does not rewrite, and a failure there
# dims a row rather than crashing.
