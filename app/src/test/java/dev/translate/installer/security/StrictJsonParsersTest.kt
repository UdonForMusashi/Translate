package dev.translate.installer.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.StandardCharsets

class StrictJsonParsersTest {
    private val parser = StrictJsonParsers()

    @Test
    fun `rejects duplicate root fields`() {
        val malformed = """
            {
              "schemaVersion": 2,
              "schemaVersion": 2
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val exception = assertThrows(BundleValidationException::class.java) {
            parser.parseManifest(malformed)
        }

        assertEquals(BundleFailureCode.JSON_INVALID, exception.code)
    }

    @Test
    fun `rejects a UTF-8 BOM`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "{}".toByteArray(StandardCharsets.UTF_8)

        val exception = assertThrows(BundleValidationException::class.java) {
            parser.parseManifest(bytes)
        }

        assertEquals(BundleFailureCode.JSON_INVALID, exception.code)
    }

    @Test
    fun `parses a strict signature envelope`() {
        val envelope = parser.parseSignatureEnvelope(
            """
                {
                  "schemaVersion": 2,
                  "keyId": "test-key",
                  "algorithm": "SHA256withECDSA",
                  "signature": "AQID"
                }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
        )

        assertEquals(2L, envelope.schemaVersion)
        assertEquals("test-key", envelope.keyId)
        assertEquals(listOf<Byte>(1, 2, 3), envelope.signature.toList())
    }
}
