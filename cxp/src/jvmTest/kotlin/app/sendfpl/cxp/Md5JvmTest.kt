package app.sendfpl.cxp

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** The MD5 against the JVM's, which every platform's build is held to through this. */
class Md5JvmTest {

    /** Every length across three block boundaries, split in two parts as the auth digest is. */
    @Test
    fun `the JVM's MD5 at every length`() {
        val random = Random(1321)
        for (n in 0..200) {
            val data = random.nextBytes(n)
            val want = MessageDigest.getInstance("MD5").digest(data)
            val cut = if (n == 0) 0 else random.nextInt(n)
            assertContentEquals(want, md5(data.copyOfRange(0, cut), data.copyOfRange(cut, n)), "length $n")
        }
    }
}
