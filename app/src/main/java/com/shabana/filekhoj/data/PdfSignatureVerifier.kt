package com.shabana.filekhoj.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.CertPathBuilder
import java.security.cert.CertStore
import java.security.cert.CollectionCertStoreParameters
import java.security.cert.PKIXBuilderParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509CertSelector
import java.security.cert.X509Certificate
import java.util.Date

data class SignatureVerificationResult(
    val status: String,
    val signatureCount: Int
)

/**
 * Verifies CMS signature bytes against the exact PDF byte ranges and evaluates the
 * signer's certificate path against Android's system CA store. Revocation status and
 * trusted timestamp evaluation are not yet performed, so this is not a full Acrobat
 * equivalence; those cases must be described as a limitation rather than overclaimed.
 */
class PdfSignatureVerifier {
    fun verify(document: PDDocument, sourcePdf: File): SignatureVerificationResult {
        val signatures = try {
            document.signatureDictionaries
        } catch (_: Exception) {
            return SignatureVerificationResult(DocumentEntity.SIGNATURE_STATUS_INCOMPLETE, 0)
        }
        if (signatures.isEmpty()) {
            return SignatureVerificationResult(DocumentEntity.SIGNATURE_STATUS_UNSIGNED, 0)
        }
        if (sourcePdf.length() > MAX_VERIFY_FILE_BYTES) {
            return SignatureVerificationResult(
                DocumentEntity.SIGNATURE_STATUS_INCOMPLETE,
                signatures.size
            )
        }
        return try {
            verify(document, sourcePdf.readBytes())
        } catch (_: Exception) {
            SignatureVerificationResult(DocumentEntity.SIGNATURE_STATUS_INCOMPLETE, signatures.size)
        }
    }

    fun verify(document: PDDocument, pdfBytes: ByteArray): SignatureVerificationResult {
        val signatures = try {
            document.signatureDictionaries
        } catch (_: Exception) {
            return SignatureVerificationResult(DocumentEntity.SIGNATURE_STATUS_INCOMPLETE, 0)
        }
        if (signatures.isEmpty()) {
            return SignatureVerificationResult(DocumentEntity.SIGNATURE_STATUS_UNSIGNED, 0)
        }

        var sawUntrusted = false
        var sawExpired = false
        var sawIncomplete = false
        var sawModifiedAfterSigning = false
        for (signature in signatures) {
            when (verifyOne(signature, pdfBytes)) {
                DocumentEntity.SIGNATURE_STATUS_INVALID ->
                    return SignatureVerificationResult(DocumentEntity.SIGNATURE_STATUS_INVALID, signatures.size)
                DocumentEntity.SIGNATURE_STATUS_UNTRUSTED -> sawUntrusted = true
                DocumentEntity.SIGNATURE_STATUS_EXPIRED -> sawExpired = true
                DocumentEntity.SIGNATURE_STATUS_INCOMPLETE -> sawIncomplete = true
                DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING -> sawModifiedAfterSigning = true
            }
        }
        val status = when {
            sawIncomplete -> DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
            sawExpired -> DocumentEntity.SIGNATURE_STATUS_EXPIRED
            sawUntrusted -> DocumentEntity.SIGNATURE_STATUS_UNTRUSTED
            sawModifiedAfterSigning -> DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING
            else -> DocumentEntity.SIGNATURE_STATUS_VERIFIED
        }
        return SignatureVerificationResult(status, signatures.size)
    }

    private fun verifyOne(signature: PDSignature, pdfBytes: ByteArray): String {
        return try {
            val byteRange = signature.byteRange
            if (byteRange == null || byteRange.size != 4 ||
                byteRange[0] != 0 ||
                byteRange[1] < 0 ||
                byteRange[2].toLong() < byteRange[0].toLong() + byteRange[1].toLong() ||
                byteRange[3] < 0 ||
                byteRange[2].toLong() + byteRange[3].toLong() > pdfBytes.size.toLong()
            ) return DocumentEntity.SIGNATURE_STATUS_INVALID

            // Validate the CMS signature over the exact bytes covered by ByteRange.
            // If a later incremental revision exists, report that separately after
            // the original signature has actually been cryptographically verified.
            val modifiedAfterSigning =
                byteRange[2].toLong() + byteRange[3].toLong() < pdfBytes.size.toLong()

            val signedContent = signature.getSignedContent(pdfBytes)
            val paddedContents = signature.getContents(pdfBytes)

            // UIDAI Aadhaar PDFs commonly use the legacy PDF subfilter
            // /adbe.x509.rsa_sha1: /Cert contains the X.509 certificate chain,
            // while /Contents contains a raw RSA signature, not CMS SignedData.
            // Parse and verify this format explicitly instead of reporting it as
            // an incomplete CMS signature.
            if (signature.subFilter == "adbe.x509.rsa_sha1") {
                return verifyLegacyX509RsaSha1(
                    signature = signature,
                    pdfBytes = pdfBytes,
                    signedContent = signedContent,
                    signatureContents = paddedContents,
                    modifiedAfterSigning = modifiedAfterSigning
                )
            }

            val cmsContents = ASN1InputStream(ByteArrayInputStream(paddedContents)).use { input ->
                input.readObject()?.encoded
            } ?: return DocumentEntity.SIGNATURE_STATUS_INVALID

            val cms = CMSSignedData(CMSProcessableByteArray(signedContent), cmsContents)
            val signers = cms.signerInfos.signers
            if (signers.isEmpty()) return DocumentEntity.SIGNATURE_STATUS_INVALID

            val certificateStore = cms.certificates
            val allCertificateHolders = certificateStore.getMatches(null).toList()
            val allCertificates = allCertificateHolders.map { holder ->
                JcaX509CertificateConverter().getCertificate(holder)
            }

            var untrusted = false
            var expired = false
            for (signer in signers) {
                // Match issuer and serial as ASN.1 values rather than converting
                // distinguished names to strings. This avoids formatting differences
                // between equivalent X.500 names and works with the bundled BC API.
                val signerId = signer.sid
                // CMS SignerIdentifier can be issuer+serial OR subjectKeyIdentifier.
                // Handle both forms: some government e-sign providers use SKI.
                val signerCertificateHolder = allCertificateHolders.firstOrNull { holder ->
                    if (signerId.subjectKeyIdentifier != null) {
                        val extension = holder.extensions?.getExtension(
                            org.bouncycastle.asn1.x509.Extension.subjectKeyIdentifier
                        )
                        val subjectKeyIdentifier = extension?.let {
                            org.bouncycastle.asn1.x509.SubjectKeyIdentifier
                                .getInstance(it.parsedValue).keyIdentifier
                        }
                        subjectKeyIdentifier != null &&
                            subjectKeyIdentifier.contentEquals(signerId.subjectKeyIdentifier)
                    } else {
                        signerId.serialNumber != null &&
                            signerId.serialNumber == holder.serialNumber &&
                            signerId.issuer != null &&
                            signerId.issuer == holder.issuer
                    }
                } ?: return DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
                val certificate = JcaX509CertificateConverter()
                    .getCertificate(signerCertificateHolder as X509CertificateHolder)

                val cryptographicallyValid = signer.verify(
                    JcaSimpleSignerInfoVerifierBuilder().build(certificate)
                )
                if (!cryptographicallyValid) return DocumentEntity.SIGNATURE_STATUS_INVALID

                try {
                    certificate.checkValidity(Date())
                } catch (_: java.security.cert.CertificateExpiredException) {
                    expired = true
                } catch (_: java.security.cert.CertificateNotYetValidException) {
                    return DocumentEntity.SIGNATURE_STATUS_UNTRUSTED
                }
                if (!hasTrustedPath(certificate, allCertificates)) untrusted = true
            }
            when {
                expired -> DocumentEntity.SIGNATURE_STATUS_EXPIRED
                untrusted -> DocumentEntity.SIGNATURE_STATUS_UNTRUSTED
                modifiedAfterSigning -> DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING
                else -> DocumentEntity.SIGNATURE_STATUS_VERIFIED
            }
        } catch (_: Exception) {
            DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
        }
    }

    private fun verifyLegacyX509RsaSha1(
        signature: PDSignature,
        pdfBytes: ByteArray,
        signedContent: ByteArray,
        signatureContents: ByteArray,
        modifiedAfterSigning: Boolean
    ): String {
        return try {
            val certObject = signature.cosObject.getDictionaryObject(COSName.CERT)
                ?: return DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
            val certificateBytes = when (certObject) {
                is COSArray -> (0 until certObject.size()).mapNotNull { index ->
                    (certObject.getObject(index) as? COSString)?.bytes
                }
                is COSString -> listOf(certObject.bytes)
                else -> emptyList()
            }
            if (certificateBytes.isEmpty()) return DocumentEntity.SIGNATURE_STATUS_INCOMPLETE

            val certificateFactory = CertificateFactory.getInstance("X.509")
            val certificates = certificateBytes.mapNotNull { bytes ->
                try {
                    certificateFactory.generateCertificate(ByteArrayInputStream(bytes)) as? X509Certificate
                } catch (_: Exception) {
                    null
                }
            }
            val signerCertificate = certificates.firstOrNull()
                ?: return DocumentEntity.SIGNATURE_STATUS_INCOMPLETE

            // PDF /Contents is padded to reserve space. RSA signatures have a fixed
            // length determined by the signer's public-key modulus.
            val rsaSignatureLength = (signerCertificate.publicKey as? java.security.interfaces.RSAPublicKey)
                ?.modulus?.bitLength()?.plus(7)?.div(8)
                ?: return DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
            if (signatureContents.size < rsaSignatureLength) {
                return DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
            }
            // /Contents is normally exactly one modulus-sized RSA signature
            // followed by zero padding. For legacy provider variants, try each
            // possible modulus-sized window in the supplied payload, but accept
            // only a window that verifies cryptographically against the exact
            // ByteRange bytes. Never mark a signature valid merely because its
            // payload length looks plausible.
            val verifier = Signature.getInstance("SHA1withRSA")
            verifier.initVerify(signerCertificate.publicKey)
            verifier.update(signedContent)
            val rawSignature = (0..(signatureContents.size - rsaSignatureLength))
                .asSequence()
                .map { offset -> signatureContents.copyOfRange(offset, offset + rsaSignatureLength) }
                .firstOrNull { candidate ->
                    try {
                        val candidateVerifier = Signature.getInstance("SHA1withRSA")
                        candidateVerifier.initVerify(signerCertificate.publicKey)
                        candidateVerifier.update(signedContent)
                        candidateVerifier.verify(candidate)
                    } catch (_: Exception) {
                        false
                    }
                } ?: return DocumentEntity.SIGNATURE_STATUS_INVALID

            val expired = try {
                signerCertificate.checkValidity(Date())
                false
            } catch (_: java.security.cert.CertificateExpiredException) {
                true
            } catch (_: java.security.cert.CertificateNotYetValidException) {
                return DocumentEntity.SIGNATURE_STATUS_UNTRUSTED
            }
            val untrusted = !hasTrustedPath(signerCertificate, certificates)
            when {
                expired -> DocumentEntity.SIGNATURE_STATUS_EXPIRED
                untrusted -> DocumentEntity.SIGNATURE_STATUS_UNTRUSTED
                modifiedAfterSigning -> DocumentEntity.SIGNATURE_STATUS_MODIFIED_AFTER_SIGNING
                else -> DocumentEntity.SIGNATURE_STATUS_VERIFIED
            }
        } catch (_: Exception) {
            DocumentEntity.SIGNATURE_STATUS_INCOMPLETE
        }
    }

    private fun hasTrustedPath(
        signerCertificate: X509Certificate,
        suppliedCertificates: Collection<X509Certificate>
    ): Boolean {
        return try {
            val trustStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            val anchors = mutableSetOf<TrustAnchor>()
            val aliases = trustStore.aliases()
            while (aliases.hasMoreElements()) {
                val certificate = trustStore.getCertificate(aliases.nextElement())
                if (certificate is X509Certificate) anchors += TrustAnchor(certificate, null)
            }
            if (anchors.isEmpty()) return false

            val selector = X509CertSelector().apply { certificate = signerCertificate }
            val parameters = PKIXBuilderParameters(anchors, selector).apply {
                isRevocationEnabled = false
                addCertStore(
                    CertStore.getInstance(
                        "Collection",
                        CollectionCertStoreParameters(suppliedCertificates.toList())
                    )
                )
                date = Date()
            }
            CertPathBuilder.getInstance("PKIX").build(parameters)
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val MAX_VERIFY_FILE_BYTES = 128L * 1024L * 1024L
    }
}
