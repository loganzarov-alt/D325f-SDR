// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <dirent.h>
#include <poll.h>
#include <time.h>
#include <sys/stat.h>
#include <sys/file.h>
#include <sys/types.h>
static volatile sig_atomic_t stopping;
static void stop_signal(int s){(void)s;stopping=1;}
static long long now_ms(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (long long)t.tv_sec*1000+t.tv_nsec/1000000;}
static int read_text(const char* path,char* text,size_t n){int f=open(path,O_RDONLY|O_CLOEXEC);if(f<0)return 0;ssize_t r=read(f,text,n-1);close(f);if(r<0)return 0;text[r]=0;return 1;}
static int write_text(const char* path,const char* text){int f=open(path,O_WRONLY|O_CLOEXEC);if(f<0)return 0;size_t n=strlen(text);ssize_t r=write(f,text,n);close(f);return r==(ssize_t)n;}
static unsigned long long identity(int pid,char* state){char p[64],b[4096];snprintf(p,sizeof p,"/proc/%d/stat",pid);if(!read_text(p,b,sizeof b))return 0;char* s=strrchr(b,')');if(!s)return 0;s+=2;*state=*s;char* save;char* v=strtok_r(s," ",&save);for(int field=3;v;field++,v=strtok_r(NULL," ",&save))if(field==22)return strtoull(v,NULL,10);return 0;}
static int detached(uid_t uid){char b[4096],n[64];if(!read_text("/proc/self/cgroup",b,sizeof b))return 0;snprintf(n,sizeof n,"/uid_%u/",uid);if(!strstr(b,n))return 1;char pid[32];snprintf(pid,sizeof pid,"%d",getpid());write_text("/acct/tasks",pid);write_text("/sys/fs/cgroup/cgroup.procs",pid);if(!read_text("/proc/self/cgroup",b,sizeof b))return 0;return !strstr(b,n);}
struct held {int pid;unsigned long long start;} frozen[512];
struct governor {char path[192],saved[128];int changed;} governors[8];
int main(int argc,char** argv){
 if(argc!=2)return 2;
 struct stat exe;if(stat("/proc/self/exe",&exe))return 2;
#ifndef TESTING
 if(getuid()!=0||!detached(exe.st_uid)){puts("FOCUS unavailable: root isolation");return 3;}
#else
 (void)detached;
#endif
 signal(SIGTERM,stop_signal);signal(SIGINT,stop_signal);signal(SIGHUP,stop_signal);signal(SIGPIPE,SIG_IGN);
 char lockpath[1024];snprintf(lockpath,sizeof lockpath,"%s.lock",argv[1]);int lock=open(lockpath,O_CREAT|O_RDWR|O_CLOEXEC,0600);if(lock<0||flock(lock,LOCK_EX|LOCK_NB)){puts("FOCUS already active");return 4;}
 FILE* conf=fopen(argv[1],"r");if(!conf)return 5;
 char names[256][256];int nameCount=0;while(nameCount<256&&fgets(names[nameCount],256,conf)){names[nameCount][strcspn(names[nameCount],"\r\n")]=0;if(names[nameCount][0])nameCount++;}fclose(conf);
 int heldCount=0;
#ifndef TESTING
 // Read every policy before writing any: cpu paths may share one policy.
 for(int c=0;c<8;c++){char available[192],data[512];snprintf(governors[c].path,192,"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor",c);snprintf(available,192,"/sys/devices/system/cpu/cpu%d/cpufreq/scaling_available_governors",c);if(read_text(available,data,sizeof data)&&strstr(data,"performance")&&read_text(governors[c].path,governors[c].saved,128))governors[c].changed=1;}
 for(int c=0;c<8;c++)if(governors[c].changed)governors[c].changed=write_text(governors[c].path,"performance");
#endif
 DIR* d=opendir("/proc");struct dirent* de;
 while(d&&(de=readdir(d))&&heldCount<512&&!stopping){int pid=atoi(de->d_name);if(pid<=1||pid==getpid()||pid==getppid())continue;char path[64],cmd[256];struct stat st;snprintf(path,sizeof path,"/proc/%d",pid);if(stat(path,&st))continue;snprintf(path,sizeof path,"/proc/%d/cmdline",pid);if(!read_text(path,cmd,sizeof cmd))continue;
#ifndef TESTING
  if(st.st_uid<10000&&strcmp(cmd,"com.android.systemui"))continue;
#endif
  int match=0;for(int n=0;n<nameCount;n++){size_t size=strlen(names[n]);if(!strncmp(cmd,names[n],size)&&(cmd[size]==0||cmd[size]==':')){match=1;break;}}if(!match)continue;
  char state;unsigned long long birth=identity(pid,&state);if(!birth||state=='T'||state=='t')continue;
  // Record first, then stop. Rollback never resumes a reused PID or an already stopped task.
  frozen[heldCount++]=(struct held){pid,birth};if(kill(pid,SIGSTOP))heldCount--;
 }
 if(d)closedir(d);
 printf("FOCUS active: paused=%d\n",heldCount);fflush(stdout);
 long long last=now_ms();const char* reason="stop";
 while(!stopping){struct pollfd p={0,POLLIN|POLLHUP,0};int r=poll(&p,1,100);if(r>0&&(p.revents&(POLLIN|POLLHUP|POLLERR))){char b[64];int n=read(0,b,sizeof b);if(n<=0){reason="app closed";break;}last=now_ms();}if(now_ms()-last>4000){reason="UI heartbeat lost";break;}}
 for(int i=heldCount-1;i>=0;i--){char state;if(identity(frozen[i].pid,&state)==frozen[i].start)kill(frozen[i].pid,SIGCONT);}
 for(int c=7;c>=0;c--)if(governors[c].changed)write_text(governors[c].path,governors[c].saved);
 printf("FOCUS restored: %s\n",reason);fflush(stdout);close(lock);return 0;
}
