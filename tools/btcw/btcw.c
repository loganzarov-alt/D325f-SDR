// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#define _GNU_SOURCE
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/timerfd.h>
#include <sys/wait.h>
#include <fcntl.h>
#include <unistd.h>
#include <signal.h>
#include <poll.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <time.h>

/* WCN36x0 SMD transport and FC04/05 payload from user's bt-cw archive.
 * No modem, NV, flash, boot or FTM operations. */
static char base[256]="/data/local/tmp/lg-btcw";
static const char *device="/dev/smd3";
static int channel,level,seconds;
static volatile sig_atomic_t stopping;
static int wakefd=-1, alarmfd=-1;
static int64_t deadline;
#ifndef TESTING
static const char *wake_name="lg_btcw_autostop";
#endif
static int64_t now_ms(void) {
    struct timespec t;
    if(clock_gettime(CLOCK_BOOTTIME,&t)) return -1;
    return (int64_t)t.tv_sec*1000+t.tv_nsec/1000000;
}
static void path(char *out,size_t n,const char *name) { snprintf(out,n,"%s/%s",base,name); }
static int open_file(const char *name,int flags) {
    char p[320]; path(p,sizeof p,name);
    return open(p,flags|O_CLOEXEC|O_NOFOLLOW,0600);
}
static int lock_file(const char *name,int nonblock) {
    int fd=open_file(name,O_RDWR|O_CREAT);
    if(fd<0) return -1;
    if(flock(fd,LOCK_EX|(nonblock?LOCK_NB:0))) {close(fd);return -1;}
    return fd;
}
static void status_save(const char *state,const char *reason) {
    char tmp[320],dest[320],text[512];
    path(tmp,sizeof tmp,"status.tmp");path(dest,sizeof dest,"status");
    int n=snprintf(text,sizeof text,"STATE=%s\nCHANNEL=%d\nFREQUENCY=%d\nLEVEL=%d\nAUTO_SECONDS=%d\nDEADLINE_MS=%lld\nPID=%d\nREASON=%s\n",state,channel,2402+channel,level,seconds,(long long)deadline,getpid(),reason);
    int fd=open(tmp,O_WRONLY|O_CREAT|O_TRUNC|O_CLOEXEC|O_NOFOLLOW,0600);
    if(fd>=0) { if(write(fd,text,n)==n) rename(tmp,dest);close(fd); }
}
static int flag_exists(void) {int fd=open_file("stop",O_RDONLY);if(fd<0)return 0;close(fd);return 1;}
static void signal_stop(int sig) {(void)sig;stopping=1;}
static void unlock_wake(void) {
#ifndef TESTING
    int fd=open("/sys/power/wake_unlock",O_WRONLY|O_CLOEXEC);
    if(fd>=0){ssize_t n=write(fd,wake_name,strlen(wake_name));(void)n;close(fd);}
#endif
}
static void release_timer(void) {
    if(alarmfd>=0) close(alarmfd);
    if(wakefd>=0) {close(wakefd);unlock_wake();}
}
static int arm_timer(void) {
    if(!seconds) return 1;
    int64_t now=now_ms();if(now<0)return 0;
    deadline=now+(int64_t)seconds*1000;
    alarmfd=timerfd_create(CLOCK_BOOTTIME_ALARM,TFD_CLOEXEC|TFD_NONBLOCK);
    if(alarmfd>=0) {
        struct itimerspec it={0};it.it_value.tv_sec=seconds;
        if(!timerfd_settime(alarmfd,0,&it,NULL)) return 1;
        close(alarmfd);alarmfd=-1;
    }
#ifdef TESTING
    wakefd=open_file("test-wakelock",O_WRONLY|O_CREAT);
    return wakefd>=0;
#else
    // Older LG kernels may lack alarm timerfd. A native kernel wakelock keeps
    // this daemon scheduled even after Activity/Android process termination.
    wakefd=open("/sys/power/wake_lock",O_WRONLY|O_CLOEXEC);
    if(wakefd<0)return 0;
    if(write(wakefd,wake_name,strlen(wake_name))!=(ssize_t)strlen(wake_name)) {
        close(wakefd);wakefd=-1;return 0;
    }
    return 1;
#endif
}
static int write_command(int fd,const unsigned char *data,size_t n) {
    ssize_t got;
    do {got=write(fd,data,n);}while(got<0&&errno==EINTR);
    return got==(ssize_t)n;
}
static int response(int fd,unsigned opcode,int timeout_ms) {
    unsigned char buffer[1024];size_t used=0;
    int64_t until=now_ms()+timeout_ms;
    while(now_ms()<until) {
        struct pollfd p={fd,POLLIN,0};
        if(poll(&p,1,50)<=0) continue;
        ssize_t n=read(fd,buffer+used,sizeof(buffer)-used);
        if(n<=0)continue;
        used+=(size_t)n;
        size_t at=0;
        while(used-at>=2) {
            size_t size=2+buffer[at+1];if(used-at<size)break;
            unsigned char *e=buffer+at;
            if(e[0]==0x0e&&size>=6&&(unsigned)(e[3]|e[4]<<8)==opcode) return e[5]==0?1:-1;
            if(e[0]==0x0f&&size>=6&&(unsigned)(e[4]|e[5]<<8)==opcode&&e[2]!=0) return -1;
            at+=size;
        }
        memmove(buffer,buffer+at,used-at);used-=at;
        if(used==sizeof buffer)used=0;
    }
    return 0;
}
static int reset_controller(int fd) {
    const unsigned char reset[]={0x03,0x0c,0};
    int sent=0;
    for(int i=0;i<3;i++) {
        if(write_command(fd,reset,sizeof reset)) {
            sent=1;if(response(fd,0x0c03,600)==1)return 2;
        }
    }
    return sent?1:0;
}
// Android 7 may kill an application's entire cpuacct process group on crash.
// setsid() alone does not leave that cgroup. Verify root control is independent.
static int in_app_group(uid_t app_uid) {
    char data[4096],needle[64];
    int fd=open("/proc/self/cgroup",O_RDONLY|O_CLOEXEC);
    if(fd<0)return -1;
    ssize_t n=read(fd,data,sizeof data-1);close(fd);
    if(n<0||n==(ssize_t)sizeof data-1)return -1;
    data[n]=0;snprintf(needle,sizeof needle,"/uid_%u/",(unsigned)app_uid);
    return strstr(data,needle)!=NULL;
}
static int detach_app_group(void) {
    struct stat st;if(stat("/proc/self/exe",&st))return 0;
    int membership=in_app_group(st.st_uid);
    if(membership==0)return 1;
    if(membership<0)return 0;
    const char *paths[]={"/acct/tasks","/sys/fs/cgroup/cgroup.procs"};
    char pid[32];int n=snprintf(pid,sizeof pid,"%d",getpid());
    for(unsigned i=0;i<sizeof paths/sizeof paths[0];i++) {
        int fd=open(paths[i],O_WRONLY|O_CLOEXEC);
        if(fd>=0){ssize_t written=write(fd,pid,n);close(fd);(void)written;}
        if(in_app_group(st.st_uid)==0)return 1;
    }
    return 0;
}
static void daemon_run(int lease,int commands) {
    close(commands);
    if(setsid()<0)_exit(2);
    pid_t child=fork();if(child<0)_exit(2);if(child>0)_exit(0);
    umask(077);
    if(chdir("/"))_exit(2);
    int null=open("/dev/null",O_RDWR);
    if(null>=0){dup2(null,0);dup2(null,1);dup2(null,2);if(null>2)close(null);}
    signal(SIGHUP,SIG_IGN);signal(SIGPIPE,SIG_IGN);
    signal(SIGTERM,signal_stop);signal(SIGINT,signal_stop);
    status_save("STARTING","INITIALIZING");
    if(!detach_app_group()) {status_save("ERROR","PROCESS_ISOLATION_FAILED");close(lease);_exit(7);}
    int fd=open(device,O_RDWR|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) {status_save("ERROR","SMD_OPEN_FAILED");close(lease);_exit(3);}
    if(!arm_timer()) {status_save("ERROR","AUTOSTOP_WAKE_UNAVAILABLE");close(fd);close(lease);_exit(4);}
    if(stopping||flag_exists()) {
        status_save("STOPPED","CANCELLED_BEFORE_TX");release_timer();close(fd);close(lease);_exit(0);
    }
    unsigned char cw[]={0x04,0xfc,0x09,0x05,(unsigned char)channel,(unsigned char)level,0x04,0x20,0,0,0,0};
    if(!write_command(fd,cw,sizeof cw)) {
        // A short write is also ambiguous: always try the reset.
        int off=reset_controller(fd);
        status_save(off==2?"STOPPED":"STOP_UNCONFIRMED","TX_WRITE_FAILED");
        release_timer();close(fd);close(lease);_exit(5);
    }
    status_save("RUNNING_SENT","TX_SENT");
    int ack=response(fd,0xfc04,200);
    const char *reason="MANUAL";
    if(ack<0) reason="TX_REJECTED";
    else {
        status_save(ack==1?"RUNNING_ACK":"RUNNING_SENT",ack==1?"HCI_ACK":"NO_HCI_ACK");
        for(;;) {
            if(stopping||flag_exists())break;
            int64_t now=now_ms();
            if(seconds&&(now<0||now>=deadline)){reason=now<0?"CLOCK_ERROR":"AUTOSTOP";break;}
            struct pollfd p={alarmfd,POLLIN,0};
            if(poll(&p,alarmfd>=0?1:0,100)>0&&(p.revents&POLLIN)) {reason="AUTOSTOP";break;}
        }
    }
    status_save("STOPPING",reason);
    int off=reset_controller(fd);
    status_save(off==2?"STOPPED":"STOP_UNCONFIRMED",off?reason:"RESET_WRITE_FAILED");
    release_timer();close(fd);close(lease);_exit(off==2?0:6);
}
/* Foreground guardian retains stdin but leaves the Android app cgroup.
 * Heartbeats come only while fresh CI8 arrives. EOF/crash/stall resets HCI. */
static int guard_run(int lease, int commands) {
    close(commands);
    signal(SIGHUP,SIG_IGN);signal(SIGPIPE,SIG_IGN);
    signal(SIGTERM,signal_stop);signal(SIGINT,signal_stop);
    if(!detach_app_group()){status_save("ERROR","PROCESS_ISOLATION_FAILED");return 7;}
    int fd=open(device,O_RDWR|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0){status_save("ERROR","SMD_OPEN_FAILED");return 3;}
    seconds=2;
    if(!arm_timer()){status_save("ERROR","GUARD_WAKE_UNAVAILABLE");close(fd);close(lease);return 4;}
    unsigned char cw[]={0x04,0xfc,0x09,0x05,(unsigned char)channel,(unsigned char)level,0x04,0x20,0,0,0,0};
    const char *reason="TX_WRITE_FAILED";
    if(write_command(fd,cw,sizeof cw)) {
        int ack=response(fd,0xfc04,300);
        if(ack>=0) {
            status_save(ack==1?"RUNNING_ACK":"RUNNING_SENT", "RX_GUARD");
            puts(ack==1?"TX RUNNING_ACK":"TX RUNNING_SENT");fflush(stdout);
            int64_t last=now_ms();
            for(;;) {
                if(stopping||flag_exists()){reason="STOP_REQUEST";break;}
                if(now_ms()-last>2000){reason="RX_HEARTBEAT_LOST";break;}
                struct pollfd p[2]={{STDIN_FILENO,POLLIN,0},{alarmfd,POLLIN,0}};
                int ready=poll(p,alarmfd>=0?2:1,100);
                if(ready>0 && alarmfd>=0 && (p[1].revents&POLLIN)){reason="RX_HEARTBEAT_LOST";break;}
                if(ready>0 && p[0].revents) {
                    char data[64];ssize_t n=read(STDIN_FILENO,data,sizeof data);
                    if(n<=0){reason="APP_PIPE_CLOSED";break;}
                    last=now_ms();
                    if(alarmfd>=0) { struct itimerspec it={0};it.it_value.tv_sec=2;
                        if(timerfd_settime(alarmfd,0,&it,NULL)){reason="GUARD_TIMER_ERROR";break;} }
                }
            }
        } else reason="TX_REJECTED";
    }
    status_save("STOPPING",reason);
    int off=reset_controller(fd);
    status_save(off==2?"STOPPED":"STOP_UNCONFIRMED",reason);
    puts(off==2?"TX STOPPED_ACK":"TX STOP_UNCONFIRMED");fflush(stdout);
    release_timer();close(fd);close(lease);return off==2?0:6;
}
static int number(const char *s,int low,int high,int *out) {
    char *end;errno=0;long n=strtol(s,&end,10);
    if(errno||!*s||*end||n<low||n>high)return 0;
    *out=(int)n;return 1;
}
static int show_status(void) {
    int lease=lock_file("session.lock",1),fd=open_file("status",O_RDONLY);
    char data[1024]={0};ssize_t n=fd>=0?read(fd,data,sizeof data-1):0;
    if(fd>=0)close(fd);
    if(n<=0)puts("STATE=IDLE");
    else {
        if(lease>=0&&(strstr(data,"STATE=RUNNING")||strstr(data,"STATE=STARTING")||strstr(data,"STATE=STOPPING")))puts("STATE=UNKNOWN\nREASON=DAEMON_GONE_USE_STOP");
        else fputs(data,stdout);
    }
    if(lease>=0)close(lease);
    printf("NOW_MS=%lld\n",(long long)now_ms());return 0;
}
int main(int argc,char **argv) {
#ifdef TESTING
    const char *b=getenv("BTCW_TEST_DIR"),*d=getenv("BTCW_TEST_DEVICE");
    if(!b||!d||strlen(b)>230)return 90;
    strcpy(base,b);device=d;
#else
    if(geteuid()!=0){puts("ERROR=ROOT_REQUIRED");return 1;}
#endif
    if(mkdir(base,0700)&&errno!=EEXIST){puts("ERROR=STATE_DIR");return 1;}
    struct stat st;
    if(lstat(base,&st)||!S_ISDIR(st.st_mode)||st.st_uid!=geteuid()||(st.st_mode&077)) {puts("ERROR=STATE_DIR_PERMISSIONS");return 1;}
    if(argc==2&&!strcmp(argv[1],"status"))return show_status();
    if(argc!=2&&argc!=5){puts("ERROR=ARGUMENTS");return 1;}
    int commands=lock_file("commands.lock",0);
    if(commands<0){puts("ERROR=COMMAND_LOCK");return 1;}
    if(!strcmp(argv[1],"guard")&&argc==5) {
        if(!number(argv[2],0,78,&channel)||!number(argv[3],0,9,&level))return 1;
        int lease=lock_file("session.lock",1);
        if(lease<0){puts("ERROR=ALREADY_RUNNING");return 2;}
        char p[320];path(p,sizeof p,"stop");unlink(p);
        return guard_run(lease,commands);
    }
    if(!strcmp(argv[1],"start")&&argc==5) {
        if(!number(argv[2],0,78,&channel)||!number(argv[3],0,9,&level)||!number(argv[4],0,86400,&seconds)) {puts("ERROR=RANGE");close(commands);return 1;}
        int lease=lock_file("session.lock",1);
        if(lease<0){puts("ERROR=ALREADY_RUNNING");close(commands);return 2;}
        unlock_wake(); // clear only our stale hold, after taking the exclusive lease
        char p[320];path(p,sizeof p,"stop");unlink(p);
        status_save("STARTING","LAUNCHING");
        pid_t child=fork();
        if(child<0){status_save("ERROR","FORK_FAILED");close(lease);close(commands);return 1;}
        if(child==0)daemon_run(lease,commands);
        close(lease);int status;while(waitpid(child,&status,0)<0&&errno==EINTR){}
        close(commands);puts("RESULT=LAUNCHED");return 0;
    }
    if(!strcmp(argv[1],"stop")&&argc==2) {
        int flag=open_file("stop",O_WRONLY|O_CREAT|O_TRUNC);
        if(flag<0){puts("ERROR=STOP_FLAG");close(commands);return 1;}
        close(flag);
        int lease=lock_file("session.lock",1);
        if(lease<0){puts("RESULT=STOP_REQUESTED");close(commands);return 0;}
        // Recovery works after a killed daemon, not just during a live session.
        int fd=open(device,O_RDWR|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
        int off=fd<0?0:reset_controller(fd);if(fd>=0)close(fd);
        unlock_wake(); // recover a sysfs hold left by a SIGKILLed native daemon
        status_save(off==2?"STOPPED":"STOP_UNCONFIRMED","MANUAL_RECOVERY");
        puts(off==2?"RESULT=RESET_ACK":"RESULT=RESET_UNCONFIRMED");
        close(lease);close(commands);return off==2?0:3;
    }
    close(commands);puts("ERROR=ARGUMENTS");return 1;
}

