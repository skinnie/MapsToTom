package app.mapstotom.core

import java.net.URLEncoder

/** Small URL splitter that, unlike java.net.URI, accepts the odd characters in Google links. */
class Url(val scheme: String, val host: String, val path: String, val query: String) {
    /** Query parameters in order; blank values are dropped (like Python's parse_qs). */
    val params: List<Pair<String, String>> by lazy {
        query.split('&').filter { it.isNotEmpty() }.mapNotNull {
            val i = it.indexOf('=')
            val k = percentDecode(if (i < 0) it else it.substring(0, i), plus = true)
            val v = if (i < 0) "" else percentDecode(it.substring(i + 1), plus = true)
            if (v.isEmpty()) null else k to v
        }
    }

    fun first(key: String): String? = params.firstOrNull { it.first == key }?.second
    fun has(key: String) = params.any { it.first == key }

    companion object {
        fun parse(s: String): Url {
            var rest = s.substringBefore('#')
            val scheme = rest.substringBefore("://", "").lowercase()
            if (rest.contains("://")) rest = rest.substringAfter("://")
            val hostEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
            val host = rest.substring(0, hostEnd).substringAfter('@').substringBefore(':').lowercase()
            rest = rest.substring(hostEnd)
            val path = rest.substringBefore('?')
            val query = if (rest.contains('?')) rest.substringAfter('?') else ""
            return Url(scheme, host, path, query)
        }
    }
}

/** Percent-decode as UTF-8; [plus] also turns '+' into a space. Bad escapes are kept as-is. */
fun percentDecode(s: String, plus: Boolean = false): String {
    if ('%' !in s && !(plus && '+' in s)) return s
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
            out.write(s.substring(i + 1, i + 3).toInt(16)); i += 3
        } else if (c == '+' && plus) {
            out.write(' '.code); i++
        } else {
            val n = if (c.isHighSurrogate() && i + 1 < s.length) 2 else 1
            out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8)); i += n
        }
    }
    return out.toString(Charsets.UTF_8.name())
}

private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

fun urlEncode(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8") }

fun htmlUnescape(s: String): String = s
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .replace("&lt;", "<").replace("&gt;", ">")
