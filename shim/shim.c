// shim.c - a tiny static x86_64 tracer that lets a foreign-libc payload run
// inside the Android app sandbox.
//
// The problem it solves
// ---------------------
// An app's seccomp filter is derived from bionic, so it only allows the
// syscalls bionic itself issues. Anything else is not merely denied - it is
// SECCOMP_RET_TRAPped, which raises SIGSYS and kills the process with rc 159
// before it can print a single line. `adb shell` and `run-as` get a much looser
// filter, which is why the same binary runs there and looks perfectly fine.
//
// Measured in the sandbox (2026-10, API 37, untrusted_app_27), the payload hits
// these trapped numbers on a normal start:
//
//   lstat(6)    poll(7)    access(21)     dup2(33)      accept(43)
//   rename(82)  mkdir(83)  rmdir(84)      creat(85)     link(86)
//   unlink(87)  symlink(88) chmod(90)     chown(92)     lchown(94)
//   mknod(133)  epoll_create(213)         epoll_wait(232)           inotify_init(253)
//
// What it does
// ------------
// Traces the payload and, at each seccomp stop, either
//   (a) rewrites the call into the allowed modern equivalent and replays it -
//       rip back 2 bytes over the `syscall` instruction, with *both* rax and
//       orig_rax set to the new number (the kernel reads rax at entry; setting
//       only orig_rax silently replays the old, blocked number), or
//   (b) suppresses the SIGSYS, which makes the kernel return -ENOSYS so the
//       payload sees a normal error instead of dying.
//
// Usage:  shim <program> [args...]
// Prints "trap <nr>-><new>" once per number on stderr; exits with the payload's
// status. PTRACE_O_EXITKILL means the payload dies with the shim, so no orphan
// server survives an app kill.
//
// No libc: raw syscalls only. Children are made with clone(SIGCHLD), not fork -
// raw fork(57) is itself trapped (bionic's fork is clone), so fork would kill
// the shim. PTRACE_O_TRACECLONE follows the payload's threads, because an
// untraced thread hitting the filter would still die.

#define SYS_write 1
#define SYS_mmap 9
#define SYS_nanosleep 35
#define SYS_clone 56
#define SYS_execve 59
#define SYS_wait4 61
#define SYS_ptrace 101
#define SYS_exit_group 231

#define SIGCHLD 17
#define SIGSYS 31
#define SIGTRAP 5

#define PTRACE_TRACEME 0
#define PTRACE_PEEKDATA 2
#define PTRACE_POKEDATA 5
#define PTRACE_CONT 7
#define PTRACE_GETREGS 12
#define PTRACE_SETREGS 13
#define PTRACE_GETSIGINFO 0x4202
#define PTRACE_SETOPTIONS 0x4200

#define PTRACE_O_TRACEFORK 0x2
#define PTRACE_O_TRACEVFORK 0x4
#define PTRACE_O_TRACECLONE 0x8
#define PTRACE_O_TRACEEXEC 0x10
#define PTRACE_O_EXITKILL 0x100000

#define WALL 0x40000000
#define STACK_SZ (1024 * 1024)
#define PROT_RW 3
#define MAP_PRIV_ANON 0x22

#define AT_FDCWD (-100L)
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_REMOVEDIR 0x200
#define CREAT_FLAGS 0x241 /* O_WRONLY|O_CREAT|O_TRUNC */

struct regs {
    unsigned long r15, r14, r13, r12, rbp, rbx, r11, r10, r9, r8;
    unsigned long rax, rcx, rdx, rsi, rdi, orig_rax, rip, cs, eflags, rsp, ss;
    unsigned long fs_base, gs_base, ds, es, fs, gs;
};

struct siginfo_seccomp {
    int signo, err, code, pad;
    unsigned long addr;
    int nr;
    unsigned arch;
};

static long sys6(long nr, long a, long b, long c, long d, long e, long f) {
    register long r10 __asm__("r10") = d;
    register long r8 __asm__("r8") = e;
    register long r9 __asm__("r9") = f;
    long ret;
    __asm__ volatile("syscall"
                     : "=a"(ret)
                     : "a"(nr), "D"(a), "S"(b), "d"(c), "r"(r10), "r"(r8), "r"(r9)
                     : "rcx", "r11", "memory");
    return ret;
}
static long sys(long nr, long a, long b, long c) { return sys6(nr, a, b, c, 0, 0, 0); }
static long sys5(long nr, long a, long b, long c, long d) { return sys6(nr, a, b, c, d, 0, 0); }

static long slen(const char *s) { long n = 0; while (s[n]) n++; return n; }
static void out(const char *s) { sys(SYS_write, 2, (long)s, slen(s)); }
static void outn(long v) {
    char b[24]; int i = 24; unsigned long u;
    if (v < 0) { out("-"); u = (unsigned long)(-v); } else u = (unsigned long)v;
    if (u == 0) { out("0"); return; }
    while (u && i > 0) { b[--i] = (char)('0' + (u % 10)); u /= 10; }
    sys(SYS_write, 2, (long)&b[i], 24 - i);
}
void *memset(void *d, int c, unsigned long n) {
    unsigned char *p = d;
    while (n--) *p++ = (unsigned char)c;
    return d;
}
static void sleep_ms(long ms) {
    long ts[2]; ts[0] = ms / 1000; ts[1] = (ms % 1000) * 1000000L;
    sys6(SYS_nanosleep, (long)ts, 0, 0, 0, 0, 0);
}

static long g_argc;
static char **g_argv;
static char **g_envp;
static void (*g_child)(void);

__attribute__((naked)) static long clone_child(void *stack) {
    __asm__ volatile(
        "mov %%rdi, %%rsi\n mov $17, %%edi\n xor %%edx, %%edx\n xor %%r10d, %%r10d\n"
        "xor %%r8d, %%r8d\n mov $56, %%eax\n syscall\n test %%rax, %%rax\n jne 1f\n"
        "call kchild\n 1: ret\n" ::: "memory");
}
void kchild(void) { g_child(); sys5(SYS_exit_group, 127, 0, 0, 0); __builtin_unreachable(); }

/*
 * Writes one word into the tracee's memory. PEEKDATA is denied in this domain
 * (-EFAULT for every address), but POKEDATA is not, which is all the poll
 * translation needs.
 */
static long poke(long pid, unsigned long addr, unsigned long val) {
    return sys6(SYS_ptrace, PTRACE_POKEDATA, pid, addr, (long)val, 0, 0);
}

/*
 * Writes a 2-word struct timespec somewhere below the tracee's stack pointer
 * and returns its address, or 0 on failure.
 *
 * The 128 bytes below rsp are the red zone, which a leaf function may be using:
 * a function that stores a temporary there, issues a syscall and reads it back
 * would be corrupted by writing at rsp-128. So aim a few frames lower, where
 * nothing can be live at a syscall boundary, and fall back towards rsp only if
 * that address is not mapped. A failed poke returns nonzero, so an unmapped
 * guess degrades to the old ENOSYS instead of faulting the tracee.
 */
static unsigned long put_timespec(long pid, struct regs *r, long ms) {
    static const long offs[3] = { 512, 256, 128 };
    unsigned long sec = (unsigned long)(ms / 1000);
    unsigned long nsec = (unsigned long)((ms % 1000) * 1000000L);

    for (int i = 0; i < 3; i++) {
        unsigned long addr = (r->rsp - (unsigned long)offs[i]) & ~15UL;
        if (poke(pid, addr, sec) < 0) continue;
        if (poke(pid, addr + 8, nsec) < 0) continue;
        return addr;
    }
    return 0;
}

/*
 * Rewrites (nr, args) in place into an equivalent syscall the filter allows and
 * returns the new number, or -1 when there is no faithful equivalent (the call
 * is then left as ENOSYS, which the payload handles as an ordinary error).
 *
 * Argument order for x86_64 syscalls: rdi, rsi, rdx, r10, r8, r9.
 */
static long translate(long nr, struct regs *r, long pid) {
    unsigned long a = r->rdi, b = r->rsi, c = r->rdx;

    switch (nr) {
    case 7: { /* poll(fds, nfds, timeout_ms) */
        // poll is TRAPped for every argument shape; ppoll is allowed. The two
        // differ only in the timeout representation, so materialise a timespec
        // in the tracee and hand that over. A negative timeout means "wait
        // forever", which ppoll spells as a NULL timespec.
        long ms = (long)(int)c;
        if (ms < 0) {
            r->rdx = 0;
        } else {
            unsigned long ts = put_timespec(pid, r, ms);
            if (ts == 0) return -1;
            r->rdx = ts;
        }
        r->r10 = 0; /* sigmask: none */
        r->r8  = 0; /* sigsetsize: ignored when sigmask is NULL */
        return 271; /* ppoll */
    }
    case 85: /* creat(path, mode) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = CREAT_FLAGS; r->r10 = b;
        return 257; /* openat */
    case 6: /* lstat(path, buf) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = b; r->r10 = AT_SYMLINK_NOFOLLOW;
        return 262; /* newfstatat */
    case 21: /* access(path, mode) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = b; r->r10 = 0;
        return 269; /* faccessat */
    case 90: /* chmod(path, mode) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = b; r->r10 = 0;
        return 268; /* fchmodat */
    case 92: /* chown(path, uid, gid) */
    case 94: /* lchown(path, uid, gid) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = b;
        r->r10 = c;                                  /* gid, 4th arg */
        r->r8 = (nr == 94) ? AT_SYMLINK_NOFOLLOW : 0; /* flags, 5th arg */
        return 260; /* fchownat */
    case 83: /* mkdir(path, mode) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = b;
        return 258; /* mkdirat */
    case 82: /* rename(old, new) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = AT_FDCWD; r->r10 = b;
        return 264; /* renameat */
    case 87: /* unlink(path) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = 0;
        return 263; /* unlinkat */
    case 84: /* rmdir(path) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = AT_REMOVEDIR;
        return 263; /* unlinkat */
    case 86: /* link(old, new) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = AT_FDCWD; r->r10 = b; r->r8 = 0;
        return 265; /* linkat(olddirfd, old, newdirfd, new, flags) */
    case 88: /* symlink(target, linkpath) */
        r->rdi = a; r->rsi = AT_FDCWD; r->rdx = b;
        return 266; /* symlinkat(target, newdirfd, linkpath) */
    case 133: /* mknod(path, mode, dev) */
        r->rdi = AT_FDCWD; r->rsi = a; r->rdx = b; r->r10 = c;
        return 259; /* mknodat */
    case 33: /* dup2(old, new) */
        r->rdx = 0; /* dup3's flags; dup2 leaves rdx arbitrary */
        return 292; /* dup3 */
    case 43: /* accept(fd, addr, len) */
        r->r10 = 0;
        return 288; /* accept4 */
    case 213: /* epoll_create(size) */
        r->rdi = 0;
        return 291; /* epoll_create1 */
    case 232: /* epoll_wait(epfd, ev, max, timeout) */
        r->r8 = 0; /* epoll_pwait's sigmask */
        return 281;
    case 253: /* inotify_init() */
        r->rdi = 0;
        return 294; /* inotify_init1 */
    default:
        return -1;
    }
}

static int seen[512];

static void child_exec(void) {
    long r = sys6(SYS_ptrace, PTRACE_TRACEME, 0, 0, 0, 0, 0);
    if (r < 0) {
        out("shim: PTRACE_TRACEME refused\n");
        sys5(SYS_exit_group, 126, 0, 0, 0);
    }
    sys6(SYS_execve, (long)g_argv[0], (long)g_argv, (long)g_envp, 0, 0, 0);
    out("shim: execve failed\n");
    sys5(SYS_exit_group, 126, 0, 0, 0);
}

__attribute__((naked, noreturn)) void _start(void) {
    __asm__ volatile("xor %%rbp, %%rbp\n mov %%rsp, %%rdi\n and $-16, %%rsp\n call kmain\n ud2\n" ::: "memory");
}

void kmain(long rsp) {
    struct regs rg;
    struct siginfo_seccomp si;
    int st;
    int exitcode = 127;
    int opts_set = 0;
    long main_pid = 0;

    g_argc = *(long *)rsp;
    g_argv = (char **)(rsp + 8);
    g_envp = g_argv + g_argc + 1;
    if (g_argc < 2) { out("usage: shim <program> [args...]\n"); sys5(SYS_exit_group, 2, 0, 0, 0); }
    g_argv = &g_argv[1]; /* the payload sees itself as argv[0] */

    long stk = sys6(SYS_mmap, 0, STACK_SZ, PROT_RW, MAP_PRIV_ANON, -1, 0);
    if (stk < 0 && stk > -4096) { out("shim: mmap failed\n"); sys5(SYS_exit_group, 125, 0, 0, 0); }
    long top = (stk + STACK_SZ) & ~15L;

    g_child = child_exec;
    main_pid = clone_child((void *)top);
    if (main_pid < 0) { out("shim: clone failed\n"); sys5(SYS_exit_group, 125, 0, 0, 0); }

    for (;;) {
        long r = sys6(SYS_wait4, -1, (long)&st, WALL, 0, 0, 0);
        if (r < 0) {
            if (r == -10 /* ECHILD */) break;
            sleep_ms(1);
            continue;
        }

        if ((st & 0xff) != 0x7f) { /* exited or killed, not a stop */
            if (r == main_pid) {
                if ((st & 0x7f) == 0) exitcode = (st >> 8) & 0xff;
                else exitcode = 128 + (st & 0x7f);
            }
            continue;
        }

        int sig = (st >> 8) & 0xff;

        if (!opts_set) {
            long o = sys6(SYS_ptrace, PTRACE_SETOPTIONS, r, 0,
                          PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK |
                          PTRACE_O_TRACEEXEC | PTRACE_O_EXITKILL, 0, 0);
            if (o < 0) { out("shim: SETOPTIONS errno="); outn(-o); out("\n"); }
            opts_set = 1;
        }

        if (sig == SIGSYS) {
            memset(&si, 0, sizeof(si));
            long g = sys6(SYS_ptrace, PTRACE_GETSIGINFO, r, 0, (long)&si, 0, 0);
            long nr = (g < 0) ? -1 : (long)si.nr;

            if (g >= 0 && si.code == 1 /* SYS_SECCOMP */ && nr >= 0 && nr < 512) {
                int first = !seen[nr];
                seen[nr] = 1;

                memset(&rg, 0, sizeof(rg));
                long gr = sys6(SYS_ptrace, PTRACE_GETREGS, r, 0, (long)&rg, 0, 0);
                long nn = (gr < 0) ? -1 : translate(nr, &rg, r);

                if (nn > 0) {
                    // The kernel takes the syscall number from rax at entry;
                    // orig_rax is merely where it records it. Setting only
                    // orig_rax replays the original blocked number and the
                    // tracee gets ENOSYS again.
                    rg.rax = (unsigned long)nn;
                    rg.orig_rax = (unsigned long)nn;
                    rg.rip -= 2; /* at a syscall stop rip is just past `syscall` */
                    sys6(SYS_ptrace, PTRACE_SETREGS, r, 0, (long)&rg, 0, 0);
                    if (first) { out("trap "); outn(nr); out("->"); outn(nn); out("\n"); }
                } else if (first) {
                    out("trap "); outn(nr); out(" enosys\n");
                }
                /* Either way the SIGSYS is suppressed: on the rewrite the
                   replay re-executes the call, otherwise it returns -ENOSYS. */
                sys6(SYS_ptrace, PTRACE_CONT, r, 0, 0, 0, 0);
                continue;
            }
            sys6(SYS_ptrace, PTRACE_CONT, r, 0, 0, 0, 0);
            continue;
        }

        if (sig == SIGTRAP) { /* exec / clone / fork event stop */
            sys6(SYS_ptrace, PTRACE_CONT, r, 0, 0, 0, 0);
            continue;
        }

        sys6(SYS_ptrace, PTRACE_CONT, r, 0, sig, 0, 0); /* real signal: deliver */
    }

    out("shim: exit "); outn(exitcode); out("\n");
    sys5(SYS_exit_group, exitcode, 0, 0, 0);
    __builtin_unreachable();
}
