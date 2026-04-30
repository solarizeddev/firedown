package com.solarized.firedown.data.entity;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.x500.X500Principal;

/**
 * Parsed TLS certificate information extracted from a {@link X509Certificate}.
 * Parcelable so it can be passed via Bundle to the dialog fragment.
 */
public class CertificateInfoEntity implements Parcelable {

    // --- Connection-level fields (from SecurityInformation) ---
    public final boolean isSecure;
    public final boolean isException;
    public final int securityMode;
    public final int mixedModeActive;
    public final int mixedModePassive;

    @NonNull
    public final String host;

    @NonNull
    public final String url;

    // --- Subject ---
    @Nullable
    public final String subjectCN;
    @Nullable
    public final String subjectOrg;
    @Nullable
    public final String subjectOrgUnit;
    @Nullable
    public final String subjectCountry;

    // --- Issuer ---
    @Nullable
    public final String issuerCN;
    @Nullable
    public final String issuerOrg;
    @Nullable
    public final String issuerCountry;

    // --- Validity ---
    public final long notBeforeMs;
    public final long notAfterMs;
    public final boolean isExpired;
    public final boolean isNotYetValid;

    // --- Key Info ---
    @Nullable
    public final String publicKeyAlgorithm;
    public final int keySize;
    @Nullable
    public final String signatureAlgorithm;
    @Nullable
    public final String serialNumber;
    public final int version;

    // --- Fingerprints ---
    @Nullable
    public final String sha256Fingerprint;
    @Nullable
    public final String sha1Fingerprint;

    // --- SANs ---
    @NonNull
    public final List<String> subjectAltNames;

    // --- Raw PEM (optional, for "copy cert" feature) ---
    @Nullable
    public final String pemEncoded;

    private CertificateInfoEntity(Builder b) {
        this.isSecure = b.isSecure;
        this.isException = b.isException;
        this.securityMode = b.securityMode;
        this.mixedModeActive = b.mixedModeActive;
        this.mixedModePassive = b.mixedModePassive;
        this.host = b.host;
        this.url = b.url;
        this.subjectCN = b.subjectCN;
        this.subjectOrg = b.subjectOrg;
        this.subjectOrgUnit = b.subjectOrgUnit;
        this.subjectCountry = b.subjectCountry;
        this.issuerCN = b.issuerCN;
        this.issuerOrg = b.issuerOrg;
        this.issuerCountry = b.issuerCountry;
        this.notBeforeMs = b.notBeforeMs;
        this.notAfterMs = b.notAfterMs;
        this.isExpired = b.isExpired;
        this.isNotYetValid = b.isNotYetValid;
        this.publicKeyAlgorithm = b.publicKeyAlgorithm;
        this.keySize = b.keySize;
        this.signatureAlgorithm = b.signatureAlgorithm;
        this.serialNumber = b.serialNumber;
        this.version = b.version;
        this.sha256Fingerprint = b.sha256Fingerprint;
        this.sha1Fingerprint = b.sha1Fingerprint;
        this.subjectAltNames = b.subjectAltNames;
        this.pemEncoded = b.pemEncoded;
    }

    // -----------------------------------------------------------------------
    //  Factory: build from GeckoView SecurityInformation
    // -----------------------------------------------------------------------

    /**
     * Parse a {@link X509Certificate} from GeckoView's SecurityInformation
     * into a structured, UI-ready {@link CertificateInfoEntity}.
     *
     * @param host         the host from SecurityInformation.host
     * @param cert         the X509Certificate (may be null for insecure)
     * @param isSecure     SecurityInformation.isSecure
     * @param isException  SecurityInformation.isException
     * @param securityMode SecurityInformation.securityMode
     * @param mixedActive  SecurityInformation.mixedModeActive
     * @param mixedPassive SecurityInformation.mixedModePassive
     */
    @NonNull
    public static CertificateInfoEntity from(
            @NonNull String url,
            @NonNull String host,
            @Nullable X509Certificate cert,
            boolean isSecure,
            boolean isException,
            int securityMode,
            int mixedActive,
            int mixedPassive) {

        Builder b = new Builder();
        b.host = host;
        b.url = url;
        b.isSecure = isSecure;
        b.isException = isException;
        b.securityMode = securityMode;
        b.mixedModeActive = mixedActive;
        b.mixedModePassive = mixedPassive;

        if (cert == null) return b.build();

        // Subject
        X500Principal subject = cert.getSubjectX500Principal();
        if (subject != null) {
            String dn = subject.getName(X500Principal.RFC2253);
            b.subjectCN = extractRdn(dn, "CN");
            b.subjectOrg = extractRdn(dn, "O");
            b.subjectOrgUnit = extractRdn(dn, "OU");
            b.subjectCountry = extractRdn(dn, "C");
        }

        // Issuer
        X500Principal issuer = cert.getIssuerX500Principal();
        if (issuer != null) {
            String dn = issuer.getName(X500Principal.RFC2253);
            b.issuerCN = extractRdn(dn, "CN");
            b.issuerOrg = extractRdn(dn, "O");
            b.issuerCountry = extractRdn(dn, "C");
        }

        // Validity
        Date notBefore = cert.getNotBefore();
        Date notAfter = cert.getNotAfter();
        if (notBefore != null) b.notBeforeMs = notBefore.getTime();
        if (notAfter != null) b.notAfterMs = notAfter.getTime();

        long now = System.currentTimeMillis();
        b.isExpired = (notAfter != null && now > notAfter.getTime());
        b.isNotYetValid = (notBefore != null && now < notBefore.getTime());

        // Key info
        b.signatureAlgorithm = friendlyAlgorithmName(cert.getSigAlgName());
        b.serialNumber = formatHex(cert.getSerialNumber().toByteArray());
        b.version = cert.getVersion();

        PublicKey pubKey = cert.getPublicKey();
        if (pubKey != null) {
            b.publicKeyAlgorithm = pubKey.getAlgorithm();
            b.keySize = getKeySize(pubKey);
        }

        // Fingerprints
        try {
            byte[] encoded = cert.getEncoded();
            b.sha256Fingerprint = fingerprint(encoded, "SHA-256");
            b.sha1Fingerprint = fingerprint(encoded, "SHA-1");
        } catch (CertificateEncodingException ignored) {
        }

        // Subject Alternative Names
        b.subjectAltNames = parseSANs(cert);

        // PEM
        try {
            byte[] encoded = cert.getEncoded();
            b.pemEncoded = "-----BEGIN CERTIFICATE-----\n"
                    + android.util.Base64.encodeToString(encoded, android.util.Base64.DEFAULT)
                    + "-----END CERTIFICATE-----";
        } catch (CertificateEncodingException ignored) {
        }

        return b.build();
    }

    // -----------------------------------------------------------------------
    //  Display helpers
    // -----------------------------------------------------------------------

    /**
     * Format a timestamp as a human-readable date string.
     */
    @NonNull
    public static String formatDate(long timestampMs) {
        if (timestampMs <= 0) return "—";
        SimpleDateFormat sdf = new SimpleDateFormat("MMM d, yyyy HH:mm:ss z", Locale.US);
        return sdf.format(new Date(timestampMs));
    }

    /**
     * Get the number of days remaining until expiry, or negative if expired.
     */
    public long daysRemaining() {
        if (notAfterMs <= 0) return 0;
        long diff = notAfterMs - System.currentTimeMillis();
        return diff / (1000 * 60 * 60 * 24);
    }

    /**
     * Human-readable security mode label.
     */
    @NonNull
    public String securityModeLabel() {
        return switch (securityMode) {
            case 1 -> "Domain Validated (DV)";
            case 2 -> "Extended Validation (EV)";
            default -> "Unknown";
        };
    }

    /**
     * Human-readable mixed content status.
     */
    @NonNull
    public String mixedContentLabel() {
        if (mixedModeActive == 2) return "Active mixed content loaded (insecure)";
        if (mixedModeActive == 1) return "Active mixed content blocked";
        if (mixedModePassive == 2) return "Passive mixed content loaded";
        if (mixedModePassive == 1) return "Passive mixed content blocked";
        return "No mixed content";
    }

    /**
     * Key description like "EC P-256" or "RSA 2048-bit".
     */
    @NonNull
    public String keyDescription() {
        if (publicKeyAlgorithm == null) return "Unknown";
        if (keySize > 0) {
            if ("EC".equals(publicKeyAlgorithm)) {
                return "EC P-" + keySize;
            }
            return publicKeyAlgorithm + " " + keySize + "-bit";
        }
        return publicKeyAlgorithm;
    }

    // -----------------------------------------------------------------------
    //  Private parsing helpers
    // -----------------------------------------------------------------------

    /**
     * Extract a single RDN value from an RFC 2253 distinguished name.
     * Handles escaped commas and quoted values.
     */
    @Nullable
    private static String extractRdn(@NonNull String dn, @NonNull String attr) {
        // Pattern: attr=value where value may be quoted or escaped
        Pattern p = Pattern.compile(
                "(?:^|,)\\s*" + Pattern.quote(attr) + "\\s*=\\s*(?:\"([^\"]*)\"|([^,]*))",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(dn);
        if (m.find()) {
            String val = m.group(1);
            if (val == null) val = m.group(2);
            return val != null ? val.trim() : null;
        }
        return null;
    }

    /**
     * Get the key size from a PublicKey instance.
     */
    private static int getKeySize(@NonNull PublicKey key) {
        if (key instanceof RSAPublicKey) {
            return ((RSAPublicKey) key).getModulus().bitLength();
        } else if (key instanceof ECPublicKey ecKey) {
            // Field size in bits
            return ecKey.getParams().getCurve().getField().getFieldSize();
        }
        return -1;
    }

    /**
     * Compute a hex-formatted fingerprint of DER-encoded certificate.
     */
    @Nullable
    private static String fingerprint(@NonNull byte[] encoded, @NonNull String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] digest = md.digest(encoded);
            return formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }

    /**
     * Format bytes as colon-separated uppercase hex.
     */
    @NonNull
    private static String formatHex(@NonNull byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format(Locale.US, "%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }

    /**
     * Parse Subject Alternative Names (DNS entries only, type 2).
     */
    @NonNull
    private static List<String> parseSANs(@NonNull X509Certificate cert) {
        try {
            Collection<List<?>> altNames = cert.getSubjectAlternativeNames();
            if (altNames == null) return Collections.emptyList();

            List<String> result = new ArrayList<>();
            for (List<?> entry : altNames) {
                if (entry.size() >= 2 && Integer.valueOf(2).equals(entry.get(0))) {
                    Object val = entry.get(1);
                    if (val instanceof String) {
                        result.add((String) val);
                    }
                }
            }
            return Collections.unmodifiableList(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Convert OID-style algorithm names to friendly display names.
     */
    @Nullable
    private static String friendlyAlgorithmName(@Nullable String oid) {
        if (oid == null) return null;
        // Common sig algorithm mapping
        return switch (oid) {
            case "SHA256withRSA", "1.2.840.113549.1.1.11" -> "SHA-256 with RSA";
            case "SHA384withRSA", "1.2.840.113549.1.1.12" -> "SHA-384 with RSA";
            case "SHA512withRSA", "1.2.840.113549.1.1.13" -> "SHA-512 with RSA";
            case "SHA256withECDSA", "1.2.840.10045.4.3.2" -> "SHA-256 with ECDSA";
            case "SHA384withECDSA", "1.2.840.10045.4.3.3" -> "SHA-384 with ECDSA";
            case "SHA1withRSA", "1.2.840.113549.1.1.5" -> "SHA-1 with RSA (weak)";
            default -> oid;
        };
    }

    // -----------------------------------------------------------------------
    //  Parcelable
    // -----------------------------------------------------------------------

    protected CertificateInfoEntity(Parcel in) {
        isSecure = in.readByte() != 0;
        isException = in.readByte() != 0;
        securityMode = in.readInt();
        mixedModeActive = in.readInt();
        mixedModePassive = in.readInt();
        host = in.readString();
        url = in.readString();
        subjectCN = in.readString();
        subjectOrg = in.readString();
        subjectOrgUnit = in.readString();
        subjectCountry = in.readString();
        issuerCN = in.readString();
        issuerOrg = in.readString();
        issuerCountry = in.readString();
        notBeforeMs = in.readLong();
        notAfterMs = in.readLong();
        isExpired = in.readByte() != 0;
        isNotYetValid = in.readByte() != 0;
        publicKeyAlgorithm = in.readString();
        keySize = in.readInt();
        signatureAlgorithm = in.readString();
        serialNumber = in.readString();
        version = in.readInt();
        sha256Fingerprint = in.readString();
        sha1Fingerprint = in.readString();
        subjectAltNames = new ArrayList<>();
        in.readStringList(subjectAltNames);
        pemEncoded = in.readString();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByte((byte) (isSecure ? 1 : 0));
        dest.writeByte((byte) (isException ? 1 : 0));
        dest.writeInt(securityMode);
        dest.writeInt(mixedModeActive);
        dest.writeInt(mixedModePassive);
        dest.writeString(host);
        dest.writeString(url);
        dest.writeString(subjectCN);
        dest.writeString(subjectOrg);
        dest.writeString(subjectOrgUnit);
        dest.writeString(subjectCountry);
        dest.writeString(issuerCN);
        dest.writeString(issuerOrg);
        dest.writeString(issuerCountry);
        dest.writeLong(notBeforeMs);
        dest.writeLong(notAfterMs);
        dest.writeByte((byte) (isExpired ? 1 : 0));
        dest.writeByte((byte) (isNotYetValid ? 1 : 0));
        dest.writeString(publicKeyAlgorithm);
        dest.writeInt(keySize);
        dest.writeString(signatureAlgorithm);
        dest.writeString(serialNumber);
        dest.writeInt(version);
        dest.writeString(sha256Fingerprint);
        dest.writeString(sha1Fingerprint);
        dest.writeStringList(subjectAltNames);
        dest.writeString(pemEncoded);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CertificateInfoEntity> CREATOR = new Creator<CertificateInfoEntity>() {
        @Override
        public CertificateInfoEntity createFromParcel(Parcel in) {
            return new CertificateInfoEntity(in);
        }

        @Override
        public CertificateInfoEntity[] newArray(int size) {
            return new CertificateInfoEntity[size];
        }
    };



    // -----------------------------------------------------------------------
    //  Builder
    // -----------------------------------------------------------------------

    private static class Builder {
        boolean isSecure;
        boolean isException;
        int securityMode;
        int mixedModeActive;
        int mixedModePassive;
        String host = "";
        String url = "";
        String subjectCN;
        String subjectOrg;
        String subjectOrgUnit;
        String subjectCountry;
        String issuerCN;
        String issuerOrg;
        String issuerCountry;
        long notBeforeMs;
        long notAfterMs;
        boolean isExpired;
        boolean isNotYetValid;
        String publicKeyAlgorithm;
        int keySize;
        String signatureAlgorithm;
        String serialNumber;
        int version;
        String sha256Fingerprint;
        String sha1Fingerprint;
        List<String> subjectAltNames = Collections.emptyList();
        String pemEncoded;

        CertificateInfoEntity build() {
            return new CertificateInfoEntity(this);
        }
    }
}
