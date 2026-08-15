package app.sendfpl.cxp

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.EINTR
import platform.posix.errno
import platform.posix.nanosleep
import platform.posix.timespec

/** As `Thread.sleep`: the whole of [millis], through any signal, and a negative one refused. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun sleepMillis(millis: Long) {
    require(millis >= 0) { "negative sleep: $millis ms" }
    memScoped {
        val ts = alloc<timespec>()
        val left = alloc<timespec>()
        ts.tv_sec = (millis / 1000).convert()
        ts.tv_nsec = ((millis % 1000) * 1_000_000).convert()
        while (nanosleep(ts.ptr, left.ptr) != 0 && errno == EINTR) {
            ts.tv_sec = left.tv_sec
            ts.tv_nsec = left.tv_nsec
        }
    }
}
