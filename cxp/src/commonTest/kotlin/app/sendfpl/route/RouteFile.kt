package app.sendfpl.route

import kotlin.io.encoding.Base64

/** A file under src/commonTest/resources/routes, as its bytes. */
internal fun routeFile(name: String): ByteArray =
    Base64.decode(ROUTE_FILES[name] ?: error("missing test resource routes/$name"))
