package app.mapstotom.link

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The phone's modem as the TomTom sees it over Bluetooth DUN (port of server/fakedun.py).
 * Identity: a Sony Ericsson K510i on Vodafone PT, which the TomTom's connect2internet.db knows
 * (another phone or network makes its data setup fail). The real phone's identity does not matter.
 */
object AtModem {
    /** Prefix match, so the longer commands come first. Empty body = just OK. */
    val REPLIES: List<Pair<String, String>> = listOf(
        "AT+CGMI" to "SonyEricsson", "AT+GMI" to "SonyEricsson", "AT+CGMM" to "AAB-1022041-BV", "AT+GMM" to "AAB-1022041-BV",
        "AT+CGMR" to "R8BA024", "AT+CGSN" to "000000000000000", "AT+CIMI" to "268011234567890",
        "AT+CSQ" to "+CSQ: 20,99", "AT+CREG?" to "+CREG: 0,1", "AT+CGREG?" to "+CGREG: 0,1",
        "AT+COPS?" to "+COPS: 0,2,\"26801\"", "AT+COPS=?" to "+COPS: (2,\"Vodafone P\",\"Vodafone\",\"26801\"),,(0-4),(0,2)",
        "AT+CPIN?" to "+CPIN: READY", "AT+CGATT?" to "+CGATT: 1", "AT+CBC" to "+CBC: 0,80",
        // handsfree (phone side of HFP/HSP)
        "AT+BRSF" to "+BRSF: 0",
        "AT+CIND=?" to "+CIND: (\"service\",(0,1)),(\"call\",(0,1)),(\"callsetup\",(0-3)),(\"callheld\",(0-2))," +
            "(\"signal\",(0-5)),(\"roam\",(0,1)),(\"battchg\",(0-5))",
        "AT+CIND?" to "+CIND: 1,0,0,0,4,0,4",
        "AT+CHLD=?" to "+CHLD: (0,1,2,3)", "AT+CLCC=?" to "", "AT+CLCC" to "",
        // phonebook: empty
        "AT+CPBS=?" to "+CPBS: (\"ME\",\"SM\",\"DC\",\"RC\",\"MC\")", "AT+CPBS?" to "+CPBS: \"ME\",0,500",
        "AT+CPBR=?" to "+CPBR: (1-500),40,24", "AT+CPBR=" to "",
        // SMS: text mode, empty stores
        "AT+CMGF=?" to "+CMGF: (0,1)", "AT+CMGF?" to "+CMGF: 1",
        "AT+CPMS=?" to "+CPMS: (\"ME\",\"SM\"),(\"ME\",\"SM\"),(\"ME\",\"SM\")",
        "AT+CPMS?" to "+CPMS: \"ME\",0,100,\"ME\",0,100,\"ME\",0,100", "AT+CPMS=" to "+CPMS: 0,100,0,100,0,100",
        "AT+CMGL" to "", "AT+CSMS=?" to "+CSMS: (0,1)", "AT+CSMS?" to "+CSMS: 0,1,1,1", "AT+CSMS=" to "+CSMS: 1,1,1",
        "AT+CNMI=?" to "+CNMI: (0,1,2,3),(0,1,2,3),(0,2),(0,1,2),(0,1)", "AT+CMGS=?" to "",
        "AT+CSCS=?" to "+CSCS: (\"GSM\",\"IRA\",\"UTF-8\")", "AT+CSCS?" to "+CSCS: \"GSM\"",
    )

    /** Reply text and whether this was a data dial (then PPP starts on the same stream). */
    fun reply(line: String): Pair<String, Boolean> {
        val cmd = line.trim().uppercase()
        if (cmd.startsWith("ATD")) {
            return if (cmd.endsWith(";")) "\r\nOK\r\n" to false else "\r\nCONNECT\r\n" to true
        }
        for ((prefix, body) in REPLIES) {
            if (cmd.startsWith(prefix)) return (if (body.isNotEmpty()) "\r\n$body\r\n" else "") + "\r\nOK\r\n" to false
        }
        return "\r\nOK\r\n" to false
    }

    private val ECHO_OFF = Regex("AT[^+]*E0.*")

    /**
     * AT loop until a data dial, then PPP on the same streams until the TomTom hangs up. Commands are
     * echoed like a real phone (ATE0 turns that off, ATE1/ATZ back on). Returns when the link ends.
     * [ppp] is only replaced by tests.
     */
    fun run(
        input: InputStream, output: OutputStream, host: LinkHost,
        ppp: (InputStream, OutputStream, LinkHost) -> Unit = { i, o, h -> PppLink(i, o, h).run() },
    ) {
        var echo = true
        val buf = StringBuilder()
        val chunk = ByteArray(256)
        while (true) {
            val n = try { input.read(chunk) } catch (e: IOException) { -1 }
            if (n < 0) { host.log("link closed by the TomTom"); return }
            for (i in 0 until n) buf.append((chunk[i].toInt() and 0xff).toChar())   // latin1
            while (true) {
                val cut = buf.indexOfFirst { it == '\r' || it == '\n' }
                if (cut < 0) break
                val line = buf.substring(0, cut)
                buf.delete(0, cut + 1)
                if (line.isBlank()) continue
                val cmd = line.trim().uppercase()
                if (ECHO_OFF.matches(cmd)) echo = false
                else if (cmd.startsWith("ATE1") || cmd.startsWith("ATZ")) echo = true
                val (rep, dial) = reply(line)
                val out = (if (echo) "$line\r" else "") + rep
                host.log("AT $line -> ${rep.trim().replace("\r\n", " ")}")
                try {
                    output.write(out.toByteArray(Charsets.ISO_8859_1)); output.flush()
                } catch (e: IOException) { host.log("link closed by the TomTom"); return }
                if (dial) {
                    host.log("dial: starting PPP")
                    ppp(input, output, host)
                    return
                }
            }
        }
    }
}

/** Serve one TomTom on a connected Bluetooth stream until it hangs up. */
fun runModem(input: InputStream, output: OutputStream, host: LinkHost) = AtModem.run(input, output, host)
