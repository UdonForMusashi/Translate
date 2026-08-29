package dev.translate.installer.security

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

fun interface PublicKeyProvider {
    fun find(keyId: String): PublicKey?
}

class X509EcPublicKeyProvider(
    encodedKeys: Map<String, String>,
) : PublicKeyProvider {
    private val keys: Map<String, PublicKey> = encodedKeys.mapValues { (_, encoded) ->
        val der = Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    override fun find(keyId: String): PublicKey? = keys[keyId]
}

object ReleaseKeyProvider : PublicKeyProvider {
    private val delegate = X509EcPublicKeyProvider(
        mapOf(
            ManifestPolicy.RELEASE_KEY_ID to
                "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwPv2BnsLwFDgP5XuYbhoQa/JNU4PGzLVlxMe0nt0ZRipoqwePD9JCoycWl87+wymvcg9LFeSnnJIfXII9UHttw==",
        ),
    )

    override fun find(keyId: String): PublicKey? = delegate.find(keyId)
}

class ManifestSignatureVerifier(
    private val publicKeyProvider: PublicKeyProvider,
) {
    fun verify(manifestBytes: ByteArray, envelope: SignatureEnvelope) {
        if (envelope.algorithm != ManifestPolicy.SUPPORTED_ALGORITHM) {
            fail(BundleFailureCode.SIGNATURE_ENVELOPE_INVALID)
        }
        val key = publicKeyProvider.find(envelope.keyId)
            ?: fail(BundleFailureCode.SIGNING_KEY_UNKNOWN)
        val valid = try {
            Signature.getInstance(ManifestPolicy.SUPPORTED_ALGORITHM).run {
                initVerify(key)
                update(manifestBytes)
                verify(envelope.signature)
            }
        } catch (exception: Exception) {
            fail(BundleFailureCode.SIGNATURE_INVALID, exception)
        }
        if (!valid) fail(BundleFailureCode.SIGNATURE_INVALID)
    }
}
