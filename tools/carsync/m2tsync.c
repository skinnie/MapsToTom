/*
 * m2tsync: runs on the TomTom and saves files the phone queued for it, over the car link's connection.
 * No libc: Linux 2.6.13, ARM920T, old ARM ABI (system calls are "swi 0x900000+nr").
 *
 * It asks http://192.168.77.1:8080/m2t/next. The answer is "M2T0\n" (nothing) or
 * "M2T1 <id> <crc32 hex> <size> <path>\n" followed by the file. The file goes to <path>.m2tnew, and only replaces
 * <path> once its size and CRC are right. Only .ov2, .bmp and .itn files inside the card can be written, plus the
 * three QuickGPSFix files in ephem/ (whole-file replace only). It asks with ?caps=ephem so the app knows it can,
 * and adds the device's GPS type, GPS program version and model so the app can prepare the right file.
 * "M2T2 <id> <crc32 hex> <size> <a|n> <path>\n" adds the data to the end of the file (a), or writes it only if the
 * file doesn't exist yet (n). A path starting "@map/" is inside the active map folder (from CurrentMap.dat).
 *
 * usage: m2tsync [ip port root once]   (the arguments are for testing)
 */
typedef unsigned int u32; typedef int s32; typedef unsigned char u8; typedef unsigned short u16;

#define SC(name, nr) __asm__(".text\n.arm\n.align 2\n.global " #name "\n" #name ":\n swi 0x900000+" #nr "\n bx lr\n")
SC(sys_exit, 1); SC(sys_read, 3); SC(sys_write, 4); SC(sys_open, 5); SC(sys_close, 6); SC(sys_unlink, 10);
SC(sys_rename, 38); SC(sys_mkdir, 39); SC(sys_socketcall, 102); SC(sys_fsync, 118); SC(sys_nanosleep, 162);
s32 sys_exit(s32); s32 sys_read(s32, void *, u32); s32 sys_write(s32, const void *, u32); s32 sys_open(const char *, s32, s32);
s32 sys_close(s32); s32 sys_unlink(const char *); s32 sys_rename(const char *, const char *); s32 sys_mkdir(const char *, s32);
s32 sys_socketcall(s32, u32 *); s32 sys_fsync(s32); s32 sys_nanosleep(const u32 *, u32 *);

void *memcpy(void *d, const void *s, u32 n) { u8 *a = d; const u8 *b = s; while (n--) *a++ = *b++; return d; }
void *memset(void *d, int c, u32 n) { u8 *a = d; while (n--) *a++ = (u8)c; return d; }

#define O_WRONLY 1
#define O_CREAT 0100
#define O_TRUNC 01000
#define MAX_FILE (16u * 1024 * 1024)
#define NET_TIMEOUT 30u /* seconds */
#define MAX_PATH 200

static u32 slen(const char *s) { u32 n = 0; while (s[n]) n++; return n; }
static char *put(char *d, const char *s) { while (*s) *d++ = *s++; *d = 0; return d; }
static int same(const char *a, const char *b, u32 n) { while (n--) if (*a++ != *b++) return 0; return 1; }
static int lower(int c) { return c >= 'A' && c <= 'Z' ? c + 32 : c; }

static void sleep_s(u32 s) { u32 t[2] = { s, 0 }; sys_nanosleep(t, 0); }

static u32 crc_table[256];
static void crc_init(void) {
    for (u32 i = 0; i < 256; i++) { u32 c = i; for (int k = 0; k < 8; k++) c = c & 1 ? 0xedb88320u ^ (c >> 1) : c >> 1; crc_table[i] = c; }
}

static u32 parse_ip(const char *s) {
    u32 ip = 0, part = 0; int shift = 0;
    for (;; s++) {
        if (*s == '.' || !*s) { ip |= part << shift; shift += 8; part = 0; if (!*s) return ip; }
        else part = part * 10 + (u32)(*s - '0');
    }
}
static u32 parse_dec(const char *s) { u32 v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (u32)(*s++ - '0'); return v; }

static s32 dial(u32 ip, u32 port) {
    u32 a[3] = { 2, 1, 0 };
    s32 fd = sys_socketcall(1, a);
    if (fd < 0) return -1;
    // A link that dies without closing must not leave the helper waiting forever: give up after NET_TIMEOUT and retry.
    u32 tv[2] = { NET_TIMEOUT, 0 }, opt[5] = { (u32)fd, 1 /* SOL_SOCKET */, 20 /* SO_RCVTIMEO */, (u32)tv, 8 };
    sys_socketcall(14, opt);
    opt[2] = 21; /* SO_SNDTIMEO */
    sys_socketcall(14, opt);
    u8 addr[16]; memset(addr, 0, 16);
    addr[0] = 2; addr[2] = (u8)(port >> 8); addr[3] = (u8)port;
    memcpy(addr + 4, &ip, 4);
    u32 c[3] = { (u32)fd, (u32)addr, 16 };
    if (sys_socketcall(3, c) < 0) { sys_close(fd); return -1; }
    return fd;
}

static int send_all(s32 fd, const char *p, u32 n) {
    while (n) { s32 w = sys_write(fd, p, n); if (w <= 0) return -1; p += w; n -= (u32)w; }
    return 0;
}

static u8 buf[4096];
static u32 have, pos;
static int next_byte(s32 fd) {
    if (pos == have) { s32 r = sys_read(fd, buf, sizeof buf); if (r <= 0) return -1; have = (u32)r; pos = 0; }
    return buf[pos++];
}
static int read_line(s32 fd, char *out, u32 max) {
    u32 n = 0;
    for (;;) {
        int c = next_byte(fd);
        if (c < 0) return -1;
        if (c == '\n') break;
        if (n + 1 >= max) return -1;
        if (c != '\r') out[n++] = (char)c;
    }
    out[n] = 0;
    return (int)n;
}

static int equal(const char *a, const char *b) { u32 n = slen(a); return n == slen(b) && same(a, b, n); }
static int is_gps(const char *p) { return equal(p, "ephem/lto.dat") || equal(p, "ephem/packedephemeris.ee") || equal(p, "ephem/ee_meta.txt"); }

/** Inside the card, no "..", no control characters, and only the file types MapsToTom sends. */
static int path_ok(const char *p) {
    u32 n = slen(p);
    if (n < 5 || n > MAX_PATH || p[0] == '/') return 0;
    for (u32 i = 0; i < n; i++) {
        u8 c = (u8)p[i];
        if (c < 32 || c == 127 || c == '\\' || c == ':') return 0;
        if (c == '.' && p[i + 1] == '.') return 0;
    }
    if (is_gps(p)) return 1;
    const char *e = p + n - 4;
    if (e[0] != '.') return 0;
    char x[3] = { (char)lower(e[1]), (char)lower(e[2]), (char)lower(e[3]) };
    return same(x, "ov2", 3) || same(x, "bmp", 3) || same(x, "itn", 3);
}

static u32 parse_hex(const char *s) {
    u32 v = 0;
    for (;; s++) {
        int c = lower(*s);
        if (c >= '0' && c <= '9') v = v * 16 + (u32)(c - '0');
        else if (c >= 'a' && c <= 'f') v = v * 16 + (u32)(c - 'a' + 10);
        else return v;
    }
}

/** The active map folder's name, from CurrentMap.dat: a u32 length, then "/mnt/sdcard/<map>/". */
static int current_map(const char *root, char *out, u32 max) {
    static char name[300], b[260];
    char *e = put(name, root); put(e, "/CurrentMap.dat");
    s32 fd = sys_open(name, 0, 0);
    if (fd < 0) return -1;
    s32 r = sys_read(fd, b, sizeof b - 1);
    sys_close(fd);
    if (r < 5) return -1;
    b[r] = 0;
    char *s = b[1] == 0 && b[2] == 0 && b[3] == 0 ? b + 4 : b;
    u32 n = 0;
    while (s[n] && s[n] != '\r' && s[n] != '\n') n++;
    while (n && (s[n - 1] == '/' || s[n - 1] == '\\')) n--;
    u32 start = n;
    while (start && s[start - 1] != '/' && s[start - 1] != '\\') start--;
    u32 len = n - start;
    if (!len || len >= max) return -1;
    for (u32 i = start; i < n; i++) {
        u8 c = (u8)s[i];
        if (c < 32 || c == ':' || (c == '.' && s[i + 1] == '.')) return -1;
        *out++ = (char)c;
    }
    *out = 0;
    return 0;
}

static int file_exists(const char *p) { s32 fd = sys_open(p, 0, 0); if (fd < 0) return 0; sys_close(fd); return 1; }

/** Copies what [path] holds into [out]. A missing file is fine: there's nothing to keep. */
static int copy_existing(s32 out, const char *path) {
    static u8 copy[4096];
    s32 in = sys_open(path, 0, 0);
    if (in < 0) return 0;
    int ok = 0;
    for (;;) {
        s32 r = sys_read(in, copy, sizeof copy);
        if (r == 0) break;
        if (r < 0 || send_all(out, (const char *)copy, (u32)r)) { ok = -1; break; }
    }
    sys_close(in);
    return ok;
}

static char *word(char *s) { while (*s && *s != ' ') s++; if (*s) *s++ = 0; return s; }

static void tell_done(u32 ip, u32 port, const char *id) {
    char q[80], *e = put(q, "GET /m2t/done?id=");
    e = put(e, id); e = put(e, " HTTP/1.0\r\n\r\n");
    s32 fd = dial(ip, port);
    if (fd < 0) return;
    send_all(fd, q, (u32)(e - q));
    while (sys_read(fd, buf, sizeof buf) > 0) {}
    sys_close(fd);
}

/** Adds "&key=value" from a one-line system file, keeping only safe characters (space becomes +). */
static char *report(char *e, const char *key, const char *file) {
    static char v[48];
    s32 fd = sys_open(file, 0, 0);
    if (fd < 0) return e;
    s32 n = sys_read(fd, v, sizeof v - 1);
    sys_close(fd);
    if (n <= 0) return e;
    e = put(put(put(e, "&"), key), "=");
    for (s32 i = 0; i < n; i++) {
        char c = v[i];
        if (c == '\n' || c == '\r' || c == 0) break;
        if (c == ' ') c = '+';
        else if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '.' || c == '_' || c == '-' || c == '(' || c == ')')) continue;
        *e++ = c;
    }
    *e = 0;
    return e;
}

/** 1 = saved a file, 0 = nothing waiting, -1 = no link or a bad transfer. */
static int fetch(u32 ip, u32 port, const char *root) {
    static char req[240];
    static char line[320], full[300], tmp[310], id[32], map[120];
    s32 fd = dial(ip, port);
    if (fd < 0) return -1;
    have = pos = 0;
    int result = -1;
    char *q = put(req, "GET /m2t/next?caps=ephem");
    q = report(q, "gpstype", "/proc/barcelona/gpstype");
    q = report(q, "gps", "/var/run/gps_version.txt");
    q = report(q, "model", "/proc/barcelona/usbname");
    q = put(q, " HTTP/1.0\r\n\r\n");
    if (send_all(fd, req, (u32)(q - req))) goto out;
    for (;;) { int n = read_line(fd, line, sizeof line); if (n < 0) goto out; if (n == 0) break; }
    if (read_line(fd, line, sizeof line) < 4) goto out;
    if (same(line, "M2T0", 4)) { result = 0; goto out; }
    int v2 = same(line, "M2T2 ", 5);
    if (!v2 && !same(line, "M2T1 ", 5)) goto out;
    char *a = line + 5, *b = word(a), *c = word(b), *path = word(c), mode = 'r';
    if (v2) { mode = path[0]; if (path[1] != ' ' || (mode != 'a' && mode != 'n')) goto out; path += 2; }
    if (slen(a) >= sizeof id) goto out;
    put(id, a);
    u32 want = parse_hex(b), size = parse_dec(c);
    int in_map = same(path, "@map/", 5) && slen(path) > 5;
    if (in_map) path += 5;
    if (size > MAX_FILE || !path_ok(path)) goto out;
    int gps = is_gps(path);
    if (gps && (in_map || mode != 'r')) goto out;
    if (in_map && current_map(root, map, sizeof map)) goto out;
    if (slen(root) + (in_map ? slen(map) + 1 : 0) + slen(path) + 2 > sizeof full) goto out;
    char *e = put(full, root); *e++ = '/';
    if (in_map) { e = put(e, map); *e++ = '/'; }
    if (gps) { put(e, "ephem"); sys_mkdir(full, 0755); }   // fails harmlessly when it's already there
    put(e, path);
    if (mode == 'n' && file_exists(full)) {   // already there: keep it, and the job is done
        sys_close(fd); fd = -1;
        tell_done(ip, port, id);
        result = 1;
        goto out;
    }
    e = put(tmp, full); put(e, ".m2tnew");
    s32 out = sys_open(tmp, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (out < 0) goto out;
    u32 crc = 0xffffffffu, left = size;
    int ok = mode != 'a' || copy_existing(out, full) == 0;
    if (!ok) left = 0;
    while (left) {
        if (pos == have) { s32 r = sys_read(fd, buf, sizeof buf); if (r <= 0) { ok = 0; break; } have = (u32)r; pos = 0; }
        u32 n = have - pos; if (n > left) n = left;
        for (u32 i = 0; i < n; i++) crc = crc_table[(crc ^ buf[pos + i]) & 255] ^ (crc >> 8);
        if (send_all(out, (const char *)buf + pos, n)) { ok = 0; break; }
        pos += n; left -= n;
    }
    if (ok && sys_fsync(out) < 0) ok = 0;
    sys_close(out);
    if (!ok || ~crc != want || sys_rename(tmp, full) < 0) { sys_unlink(tmp); goto out; }
    sys_close(fd); fd = -1;
    tell_done(ip, port, id);
    result = 1;
out:
    if (fd >= 0) sys_close(fd);
    return result;
}

__asm__(".text\n.arm\n.global _start\n_start:\n mov r0, sp\n bl run\n");

void run(u32 *sp) {
    u32 argc = sp[0]; char **argv = (char **)(sp + 1);
    u32 ip = parse_ip(argc > 1 ? argv[1] : "192.168.77.1"), port = argc > 2 ? parse_dec(argv[2]) : 8080;
    const char *root = argc > 3 ? argv[3] : "/mnt/sdcard";
    int once = argc > 4;
    crc_init();
    for (;;) {
        int r = fetch(ip, port, root);
        if (once && r <= 0) sys_exit(r < 0);
        if (r <= 0) sleep_s(r == 0 ? 20 : 30);
    }
}
