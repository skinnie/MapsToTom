package app.mapstotom

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Optional, for rooted devices: mounts a USB TomTom that Android refuses or keeps for itself. Without root the app uses
 * Android's folder picker (Choose drive).
 *
 * Old TomToms (ONE, XL, GO) carry FAT straight on the disk with no partition table, which
 * Android's storage service rejects, and they won't boot if the card is given one. With root
 * the disk is mounted inside this app's own mount namespace, on files/tomtom, owned by the app,
 * so it is visible to MapsToTom only and is read like any folder.
 */
class RootUsb(private val ctx: Context) {
    /** Real path (on some builds /data/user/0 is a link to /data/data, and /proc/mounts shows the real one). */
    val dir: File get() = File(ctx.filesDir, "tomtom").canonicalFile

    /** Why the last mount found nothing, for the status line. Null when it worked. */
    var note: String? = null; private set

    fun isMounted(): Boolean = runCatching {
        File("/proc/self/mounts").readLines().any { it.split(' ').getOrNull(1) == dir.absolutePath }
    }.getOrDefault(false)

    private fun hasBif() = dir.list()?.any { it.equals("ttgo.bif", ignoreCase = true) } == true

    /** Mount the first USB disk holding a TomTom. Returns the folder, or null (see [note]). */
    fun mount(): File? {
        if (isMounted() && hasBif()) { note = null; return dir }
        dir.mkdirs()
        val r = runRoot(MOUNT_SCRIPT, dir.absolutePath, android.os.Process.myUid().toString())
        note = when {
            r == null -> "Press Choose drive and pick the TomTom. If Android doesn't list it, use a computer or a card reader."
            isMounted() && hasBif() -> null
            "NODISK" in r -> "No USB drive found. Plug the TomTom in with an OTG adapter and choose \"Connect to computer\" on it."
            "HELD" in r -> "Android is still using this drive, so MapsToTom can't open it. Unplug it and plug it in again."
            else -> "A USB drive is connected but it isn't a TomTom, or it couldn't be mounted."
        }
        return if (note == null) dir else null
    }

    /** Flush and unmount. True when nothing is mounted afterwards. */
    fun eject(): Boolean {
        if (!isMounted()) return true
        runRoot(UMOUNT_SCRIPT, dir.absolutePath)
        return !isMounted()
    }

    /**
     * Runs a script as root in this app's mount namespace (nsenter, in case su starts in its own).
     * Returns its output, or null when su is missing or refused.
     */
    private fun runRoot(script: String, vararg args: String): String? {
        val f = File(ctx.cacheDir, "root.sh").apply { writeText(script) }
        val pid = android.os.Process.myPid()
        val quoted = args.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        val cmd = "if command -v nsenter >/dev/null; then nsenter -t $pid -m sh ${f.absolutePath} $quoted; " +
            "else sh ${f.absolutePath} $quoted; fi"
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroy(); return out }
            if (p.exitValue() == 1 && out.isBlank()) null else out // refused by the root manager
        } catch (e: java.io.IOException) {
            null // no su binary
        }
    }

    private companion object {
        val MOUNT_SCRIPT = """
            DIR=${'$'}(readlink -f "${'$'}1"); U="${'$'}2"
            if grep -q " ${'$'}DIR " /proc/mounts; then umount "${'$'}DIR" 2>/dev/null || umount -l "${'$'}DIR"; fi
            CTX=${'$'}(ls -Zd "${'$'}DIR" | awk '{print ${'$'}1}')
            BASE="uid=${'$'}U,gid=${'$'}U,fmask=0117,dmask=0007"
            found=0; held=0
            # Android 14 mounts partitionless FAT itself. A second mount would share its superblock and keep its owner,
            # so the app couldn't read it: ask vold to let go first.
            release() {
              dev=${'$'}(cat /sys/block/${'$'}1/dev); maj=${'$'}{dev%:*}; min=${'$'}{dev#*:}
              held_ids() {
                grep -oE "public:${'$'}maj,[0-9]+" /proc/1/mounts | sort -u | while read -r v; do
                  m=${'$'}{v##*,}
                  [ "${'$'}m" -ge "${'$'}min" ] && [ "${'$'}m" -lt ${'$'}((min + 16)) ] && echo "${'$'}v"
                done
              }
              ids=${'$'}(held_ids)
              [ -n "${'$'}ids" ] || return 0
              for v in ${'$'}ids; do sm unmount "${'$'}v" >/dev/null 2>&1; done
              i=0
              while [ ${'$'}i -lt 40 ] && [ -n "${'$'}(held_ids)" ]; do sleep 0.25; i=${'$'}((i + 1)); done
            }
            for d in /sys/block/sd*; do
              [ -e "${'$'}d" ] || continue
              found=1; n=${'$'}{d##*/}
              release "${'$'}n"
              # whole disk first (TomTom layout), then the first partition
              for p in /dev/block/${'$'}n /dev/block/${'$'}{n}1; do
                [ -b "${'$'}p" ] || continue
                for o in "${'$'}BASE,shortname=mixed,utf8,flush,context=\"${'$'}CTX\"" "${'$'}BASE,context=\"${'$'}CTX\"" "${'$'}BASE"; do
                  if mount -t vfat -o "${'$'}o" "${'$'}p" "${'$'}DIR" 2>/dev/null; then
                    if [ "${'$'}(stat -c %u "${'$'}DIR")" != "${'$'}U" ]; then umount "${'$'}DIR"; held=1; break; fi
                    if ls "${'$'}DIR" | grep -qi '^ttgo.bif${'$'}'; then echo "OK ${'$'}p"; exit 0; fi
                    umount "${'$'}DIR"; break
                  fi
                done
              done
            done
            [ ${'$'}held = 1 ] && { echo HELD; exit 3; }
            [ ${'$'}found = 1 ] && echo NOTTOMTOM || echo NODISK
            exit 3
        """.trimIndent()

        val UMOUNT_SCRIPT = """
            sync
            set -- "${'$'}(readlink -f "${'$'}1")"
            umount "${'$'}1" 2>/dev/null || { sleep 1; sync; umount "${'$'}1"; }
            sync
        """.trimIndent()
    }
}
