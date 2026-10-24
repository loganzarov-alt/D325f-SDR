// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <sys/types.h>
#include <unistd.h>
static int events[10],nevents,ioctl_fail,advice_fail,direct_required,direct_reads,original_fd;
static int checked_fsync(int fd) { events[nevents++]=1;return fsync(fd); }
static int checked_advice(int fd,off_t off,off_t n,int advice) {
    events[nevents++]=3;
    assert(off==0 && n==0 && advice==POSIX_FADV_DONTNEED);
    return advice_fail?EIO:posix_fadvise(fd,off,n,advice);
}
static int checked_ioctl(int fd,unsigned long request,...) {
    (void)fd;events[nevents++]=2;assert(request==_IO(0x12,97));
    va_list args;va_start(args,request);assert(va_arg(args,int)==0);va_end(args);
    if(ioctl_fail){errno=EIO;return -1;}return 0;
}
static ssize_t checked_pread(int fd,void *buffer,size_t n,off_t off) {
    if(direct_required){assert(fd!=original_fd);assert(fcntl(fd,F_GETFL)&O_DIRECT);assert((uintptr_t)buffer%4096==0);assert(n%4096==0 && off%4096==0);direct_reads++;}
    return pread(fd,buffer,n,off);
}
#define fsync checked_fsync
#define posix_fadvise checked_advice
#define ioctl checked_ioctl
#define pread checked_pread
#define main modem_engine_main
#include "../recovery/modem-toggle.c"
#undef main
#undef fsync
#undef posix_fadvise
#undef ioctl
#undef pread
int main(void) {
    char path[]="/tmp/d325-cache-contract-XXXXXX";int fd=mkstemp(path);assert(fd>=0);original_fd=fd;
    assert(!ftruncate(fd,IMAGE_BYTES));
    assert(!flush_cache(fd,1));assert(nevents==2 && events[0]==1 && events[1]==2);
    nevents=0;ioctl_fail=1;assert(flush_cache(fd,1)==-1 && errno==EIO);assert(nevents==2);ioctl_fail=0;
    nevents=0;assert(!flush_cache(fd,0));assert(nevents==2 && events[0]==1 && events[1]==3);
    nevents=0;advice_fail=1;assert(flush_cache(fd,0)==-1 && errno==EIO);advice_fail=0;
    nevents=0;direct_required=1;uint8_t hash[32];assert(!hash_fd(fd,path,1,hash));assert(direct_reads==IMAGE_BYTES/IO_BYTES);assert(nevents==2 && events[0]==1 && events[1]==2);
    close(fd);unlink(path);puts("PASS cache contract: fsync, ioctl/fadvise errors, separate aligned O_DIRECT descriptor");return 0;
}
