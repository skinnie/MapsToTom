package app.mapstotom.core

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class HttpResponse(val code: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text get() = body.toString(Charsets.UTF_8)
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
}

/** Everything that talks to the internet goes through this, so tests can fake it. */
interface Net {
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        timeoutMs: Int = 15_000,
    ): HttpResponse
}

class RealNet : Net {
    override fun get(url: String, headers: Map<String, String>, followRedirects: Boolean, timeoutMs: Int): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = followRedirects
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        if (c is HttpsURLConnection && Url.parse(url).host.endsWith("tomtom.com")) {
            c.sslSocketFactory = TomTomTls.context.socketFactory
        }
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val h = c.headerFields.filterKeys { it != null }.mapValues { it.value.lastOrNull() ?: "" }
            return HttpResponse(code, h, body)
        } finally {
            c.disconnect()
        }
    }
}

/**
 * TomTom's download server still uses a certificate that chains to the old
 * "DigiCert High Assurance EV Root CA", which newer systems no longer trust.
 * We trust the normal system roots plus exactly that one root, only for tomtom.com.
 * SHA-256 fingerprint: 74:31:E5:F4:C3:C1:CE:46:90:77:4F:0B:61:E0:54:40:88:3B:A9:A0:1E:D0:0B:A6:AB:D7:80:6E:D3:B1:18:CF
 */
object TomTomTls {
    const val DIGICERT_HA_EV_ROOT = """-----BEGIN CERTIFICATE-----
MIIDxTCCAq2gAwIBAgIQAqxcJmoLQJuPC3nyrkYldzANBgkqhkiG9w0BAQUFADBs
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
d3cuZGlnaWNlcnQuY29tMSswKQYDVQQDEyJEaWdpQ2VydCBIaWdoIEFzc3VyYW5j
ZSBFViBSb290IENBMB4XDTA2MTExMDAwMDAwMFoXDTMxMTExMDAwMDAwMFowbDEL
MAkGA1UEBhMCVVMxFTATBgNVBAoTDERpZ2lDZXJ0IEluYzEZMBcGA1UECxMQd3d3
LmRpZ2ljZXJ0LmNvbTErMCkGA1UEAxMiRGlnaUNlcnQgSGlnaCBBc3N1cmFuY2Ug
RVYgUm9vdCBDQTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAMbM5XPm
+9S75S0tMqbf5YE/yc0lSbZxKsPVlDRnogocsF9ppkCxxLeyj9CYpKlBWTrT3JTW
PNt0OKRKzE0lgvdKpVMSOO7zSW1xkX5jtqumX8OkhPhPYlG++MXs2ziS4wblCJEM
xChBVfvLWokVfnHoNb9Ncgk9vjo4UFt3MRuNs8ckRZqnrG0AFFoEt7oT61EKmEFB
Ik5lYYeBQVCmeVyJ3hlKV9Uu5l0cUyx+mM0aBhakaHPQNAQTXKFx01p8VdteZOE3
hzBWBOURtCmAEvF5OYiiAhF8J2a3iLd48soKqDirCmTCv2ZdlYTBoSUeh10aUAsg
EsxBu24LUTi4S8sCAwEAAaNjMGEwDgYDVR0PAQH/BAQDAgGGMA8GA1UdEwEB/wQF
MAMBAf8wHQYDVR0OBBYEFLE+w2kD+L9HAdSYJhoIAu9jZCvDMB8GA1UdIwQYMBaA
FLE+w2kD+L9HAdSYJhoIAu9jZCvDMA0GCSqGSIb3DQEBBQUAA4IBAQAcGgaX3Nec
nzyIZgYIVyHbIUf4KmeqvxgydkAQV8GK83rZEWWONfqe/EW1ntlMMUu4kehDLI6z
eM7b41N5cdblIZQB2lWHmiRk9opmzN6cN82oNLFpmyPInngiK3BD41VHMWEZ71jF
hS9OMPagMRYjyOfiZRYzy78aG6A9+MpeizGLYAiJLQwGXFK3xPkKmNEVX58Svnw2
Yzi9RKR/5CYrCsSXaQ3pjOLAEFe4yHYSkVXySGnYvCoCWw9E1CAx2/S6cCZdkGCe
vEsXCS+0yx5DaMkHJ8HSXPfqIbloEpw8nL+e/IBcm2PN7EeqJSdnoDfzAIJ9VNep
+OkuE6N36B9K
-----END CERTIFICATE-----"""

    val context: SSLContext by lazy {
        val cf = CertificateFactory.getInstance("X.509")
        val extra = cf.generateCertificate(ByteArrayInputStream(DIGICERT_HA_EV_ROOT.toByteArray())) as X509Certificate
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        // System roots first ...
        val sysTmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val sysTm = sysTmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        sysTm.acceptedIssuers.forEachIndexed { i, c -> ks.setCertificateEntry("sys$i", c) }
        // ... plus the one old DigiCert root.
        ks.setCertificateEntry("digicert-ha-ev-root", extra)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
    }
}
