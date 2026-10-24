// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
typedef unsigned int u32;
typedef unsigned short u16;
typedef unsigned char u8;

static long call3(long nr, long a, long b, long c) {
    register long r0 __asm__("r0")=a;
    register long r1 __asm__("r1")=b;
    register long r2 __asm__("r2")=c;
    register long r7 __asm__("r7")=nr;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1),"r"(r2),"r"(r7) : "memory");
    return r0;
}

/* Linux ARM EABI syscalls used by this freestanding Android helper. */
static long sys_open(const char *path, int flags, int mode) {
    register const char *r0 __asm__("r0") = path;
    register int r1 __asm__("r1") = flags;
    register int r2 __asm__("r2") = mode;
    register long r7 __asm__("r7") = 5;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return (long)r0;
}

static long sys_read(int fd, void *buf, u32 len) {
    register long r0 __asm__("r0") = fd;
    register void *r1 __asm__("r1") = buf;
    register u32 r2 __asm__("r2") = len;
    register long r7 __asm__("r7") = 3;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
}

static long sys_write(int fd, const void *buf, u32 len) {
    if(fd==1) {
        u32 sent=0;
        while(sent<len) {
            long got=call3(4,fd,(long)((const u8*)buf+sent),len-sent);
            if(got>0){sent+=got;continue;}
            if(got==-4)continue;
            if(got==-11){long pause[2];pause[0]=0;pause[1]=1000000;call3(162,(long)pause,0,0);continue;}
            call3(248,20,0,0);return got;
        }
        return sent;
    }
    register long r0 __asm__("r0") = fd;
    register const void *r1 __asm__("r1") = buf;
    register u32 r2 __asm__("r2") = len;
    register long r7 __asm__("r7") = 4;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
}

static long sys_ioctl(int fd, u32 cmd, void *arg) {
    register long r0 __asm__("r0") = fd;
    register u32 r1 __asm__("r1") = cmd;
    register void *r2 __asm__("r2") = arg;
    register long r7 __asm__("r7") = 54;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
}

static long sys_alarm(u32 seconds) {
    /* ARM EABI has no legacy alarm syscall. ITIMER_REAL uses setitimer. */
    u32 timer[4];
    timer[0] = 0; timer[1] = 0; timer[2] = seconds; timer[3] = 0;
    register long r0 __asm__("r0") = 0;
    register void *r1 __asm__("r1") = timer;
    register long r2 __asm__("r2") = 0;
    register long r7 __asm__("r7") = 104;
    __asm__ volatile("svc 0" : "+r"(r0) : "r"(r1), "r"(r2), "r"(r7) : "memory");
    return r0;
}

static __attribute__((noreturn)) void sys_exit(int code) {
    register long r0 __asm__("r0") = code;
    register long r7 __asm__("r7") = 248;
    __asm__ volatile("svc 0" : : "r"(r0), "r"(r7) : "memory");
    for (;;) {}
}

static void out(const char *s) {
    u32 n = 0;
    while (s[n]) n++;
    sys_write(1, s, n);
}

static void out_hex(u32 value) {
    static const char digits[] = "0123456789abcdef";
    char b[11];
    int i;
    b[0] = '0'; b[1] = 'x';
    for (i = 0; i < 8; i++) b[2 + i] = digits[(value >> (28 - 4 * i)) & 15];
    b[10] = '\n';
    sys_write(1, b, sizeof(b));
}

static void out_bytes(const u8 *p, u32 n) {
    static const char digits[] = "0123456789abcdef";
    char encoded[1025];
    u32 i;
    if(n>512)return;
    for (i = 0; i < n; i++) {
        encoded[2*i] = digits[p[i] >> 4];
        encoded[2*i+1] = digits[p[i] & 15];
    }
    encoded[2*n]='\n';sys_write(1,encoded,2*n+1);
}

static u32 le32(const u8 *p) { return p[0]|((u32)p[1]<<8)|((u32)p[2]<<16)|((u32)p[3]<<24); }
static unsigned le16(const u8 *p) { return p[0]|(p[1]<<8); }
static void put16(u8 *p,unsigned v) { p[0]=v;p[1]=v>>8; }
static void put32(u8 *p,u32 v) { put16(p,v);put16(p+2,v>>16); }
static unsigned crc(const u8 *p,unsigned n) {
    unsigned c=65535;
    for(unsigned i=0;i<n;i++) { c^=p[i];for(int j=0;j<8;j++)c=(c>>1)^((c&1)?0x8408:0); }
    return c^65535;
}
static void decimal(u32 v) {
    char buf[12];unsigned n=0;
    do { buf[n++]='0'+v%10;v/=10; } while(v);
    while(n)sys_write(1,&buf[--n],1);
}
static u8 readbuf[1048576], packet[8192], answer[8192];
static unsigned packetlen, escaped, answerlen;
static int feed(u8 b,unsigned command) {
    if(b==0x7e) {
        unsigned n=packetlen;packetlen=0;escaped=0;
        if(n<3 || crc(packet,n-2)!=le16(packet+n-2))return 0;
        if(packet[0]==0x13 || packet[0]==0x14 || packet[0]==0x15)return -1;
        if(n<8 || packet[0]!=75 || packet[1]!=11 || le16(packet+2)!=1 || le16(packet+4)!=command)return 0;
        answerlen=n-2;for(unsigned i=0;i<answerlen;i++)answer[i]=packet[i];return 1;
    }
    if(b==0x7d && !escaped){escaped=1;return 0;}
    if(escaped){b^=32;escaped=0;}
    if(packetlen>=sizeof(packet)){packetlen=0;return -1;}
    packet[packetlen++]=b;return 0;
}
static int transaction(int fd,unsigned cmd,u32 a,u32 b) {
    u8 req[24];for(unsigned i=0;i<sizeof(req);i++)req[i]=0;
    put32(req,128);req[4]=75;req[5]=11;put16(req+6,1);put16(req+8,cmd);
    if(cmd==7 || cmd==8)put16(req+10,a);
    else {put32(req+12,a);put32(req+16,b);}
    packetlen=0;escaped=0;answerlen=0;
    if(sys_alarm(5)<0)return -1;
    if(sys_write(fd,req,sizeof(req))<0)return -1;
    for(unsigned tries=0;tries<100;tries++) {
        long got=sys_read(fd,readbuf,sizeof(readbuf));
        if(got<8 || (u32)got>sizeof(readbuf))return -1;
        if(le32(readbuf)!=32)continue;
        u32 count=le32(readbuf+4),pos=8;
        if(count>1024)return -1;
        for(u32 k=0;k<count;k++) {
            if(pos+4>(u32)got)return -1;
            u32 len=le32(readbuf+pos);pos+=4;
            if(len>(u32)got-pos)return -1;
            for(u32 j=0;j<len;j++){int rc=feed(readbuf[pos+j],cmd);if(rc)return rc;}
            pos+=len;
        }
    }
    return -1;
}
static int run(void) {
 long lock=sys_open("/data/local/tmp/lg-iq-sampler.lock",66,0600);
 if(lock<0 || call3(143,lock,6,0)<0)return 4;
 long fd=sys_open("/dev/diag",2,0);
 if(fd<0 || sys_ioctl(fd,7,(void*)6)<0)return 5;
 if(transaction(fd,7,9,0)!=1)return 6;
 unsigned channels[3]={9750,9760,9750};
 for(unsigned i=0;i<3;i++) {
   if(transaction(fd,8,channels[i],0)!=1)return 7;
   out("TUNE ");decimal(channels[i]);out(" ");out_bytes(answer,answerlen);
   long pause[2]={0,200000000};call3(162,(long)pause,0,0);
   if(transaction(fd,262,0,0)!=1)return 8;
   out("PLL ");out_bytes(answer,answerlen);
 }
 return 0;
}
__attribute__((noreturn)) void _start(void){sys_exit(run());}
