package kr.co.hanium.dreamup.walksafe.network

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class InstallationEvidenceSignatureTest {
    @Test fun p256SignatureBindsExactDomainAndEvidenceDigestAndRejectsOtherKeyOrDigest() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        val digest = "a".repeat(64)
        val expected = ("walksafe/device-deletion-evidence-signature/v1\u0000" + digest).toByteArray(Charsets.US_ASCII)
        assertArrayEquals(expected, installationEvidenceSignatureMessage(digest))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private); update(expected)
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        assertTrue(verifyInstallationEvidenceSignature(pair.public, digest, signature))
        assertFalse(verifyInstallationEvidenceSignature(pair.public, "b".repeat(64), signature))
        assertFalse(verifyInstallationEvidenceSignature(generator.generateKeyPair().public, digest, signature))
        assertFalse(verifyInstallationEvidenceSignature(pair.public, digest, signature + "="))
        assertFalse(verifyInstallationEvidenceSignature(pair.public, digest, "invalid"))
    }

    @Test fun registrationBindingSeparatesActorsWithTheSameGenerationAndInstallation() {
        val origin = "https://gateway.example.test"
        val a = installationEvidenceOwnerBindingHash(origin, "a".repeat(64), 1L)
        val b = installationEvidenceOwnerBindingHash(origin, "b".repeat(64), 1L)
        assertNotEquals(a, b)
        assertNotEquals(a, installationEvidenceOwnerBindingHash(origin, "a".repeat(64), 2L))
        assertNotEquals(a, installationEvidenceOwnerBindingHash("https://other.example.test", "a".repeat(64), 1L))
    }

    @Test fun onlyCanonicalUncompressedP256PublicKeysAreAdmitted() {
        fun key(curve: String) = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }
            .generateKeyPair().public.encoded
        val bytes = key("secp256r1")
        assertEquals(91, bytes.size)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        assertTrue(validInstallationEvidencePublicKey(encoded))
        assertFalse(validInstallationEvidencePublicKey(encoded + "="))
        assertFalse(validInstallationEvidencePublicKey(Base64.getUrlEncoder().withoutPadding().encodeToString(key("secp384r1"))))
        assertFalse(validInstallationEvidencePublicKey(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(91))))
    }
}
