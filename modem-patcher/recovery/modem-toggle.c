// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include "sha256.h"

#define BLKROSET _IO(0x12,93)
#define BLKROGET _IO(0x12,94)
#define BLKFLSBUF _IO(0x12,97)
#define BLKGETSIZE64 _IOR(0x12,114,size_t)
#define IMAGE_BYTES (64u * 1024u * 1024u)
#define IO_BYTES 65536u
#define DEFAULT_DUMPS "/data/media/0/D325f"
struct run { uint32_t at, n; const uint8_t *mask; };
struct file { char name[65]; uint32_t size,nruns,offset; uint8_t stock[32],patched[32]; struct run *runs; };
struct payload { uint8_t *bytes; size_t n,pos; uint32_t count; struct file files[16]; };
struct fat { const uint8_t *image; uint32_t cluster,fat,fatbytes,root,rootentries,data; };
enum { STOCK=1, PATCHED=2, MIXED=3, UNKNOWN=4 };
static volatile sig_atomic_t interrupted;
static int original_ro=-1,ro_fd=-1;
static unsigned changed_sectors;
#ifdef D325_TESTING
static unsigned flush_calls;
static int testing_fixture;
#endif
static void stop_signal(int sig) { (void)sig; interrupted=1; }
static void cleanup(void) {
    if (ro_fd>=0) {
        if (original_ro==1 && ioctl(ro_fd,BLKROSET,&original_ro))
            fprintf(stderr,"ERROR: Could not restore the block read-only flag\n");
        close(ro_fd);
    }
}
static void die(const char *text) { fprintf(stderr,"ERROR: %s\n",text); exit(2); }
static uint16_t le16(const uint8_t *p) { return (uint16_t)p[0]|(uint16_t)p[1]<<8; }
static uint32_t le32(const uint8_t *p) { return (uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24; }
static const uint8_t *take(struct payload *p,size_t n) {
    if (p->pos>p->n || n>p->n-p->pos) die("Truncated patch payload");
    const uint8_t *r=p->bytes+p->pos;
    p->pos+=n;
    return r;
}
static uint32_t number(struct payload *p) { return le32(take(p,4)); }
static void load_payload(struct payload *p,const char *path) {
    FILE *f=fopen(path,"rb");
    if (!f) die("Cannot open patch payload");
    if (fseek(f,0,SEEK_END)) die("Cannot seek in patch payload");
    long n=ftell(f);
    if (n<12 || n>1048576) die("Invalid patch payload length");
    rewind(f);
    p->bytes=malloc((size_t)n);
    p->n=(size_t)n;
    p->pos=0;
    if (!p->bytes || fread(p->bytes,1,p->n,f)!=p->n) die("Cannot read patch payload");
    fclose(f);
    if (memcmp(take(p,8),"D325PT03",8)) die("Unsupported patch format");
    p->count=number(p);
    if (!p->count || p->count>16) die("Invalid patch file count");
    for (uint32_t i=0;i<p->count;i++) {
        struct file *a=&p->files[i];
        uint32_t len=number(p);
        if (!len || len>64) die("Invalid patch name length");
        memcpy(a->name,take(p,len),len);
        a->name[len]=0;
        if (strncmp(a->name,"IMAGE/MODEM.",12) || strstr(a->name,"..")) die("Unexpected firmware path");
        a->size=number(p);
        if (!a->size || a->size>IMAGE_BYTES) die("Invalid firmware size");
        memcpy(a->stock,take(p,32),32);
        memcpy(a->patched,take(p,32),32);
        if (!memcmp(a->stock,a->patched,32)) die("Ambiguous patch hashes");
        a->nruns=number(p);
        if (!a->nruns || a->nruns>4096) die("Invalid patch run count");
        a->runs=calloc(a->nruns,sizeof(*a->runs));
        if (!a->runs) die("Cannot allocate patch runs");
        uint32_t end=0;
        for (uint32_t j=0;j<a->nruns;j++) {
            struct run *r=&a->runs[j];
            r->at=number(p);
            r->n=number(p);
            if (!r->n || r->at<end || r->n>a->size || r->at>a->size-r->n) die("Invalid or overlapping patch run");
            r->mask=take(p,r->n);
            end=r->at+r->n;
        }
    }
    if (p->pos!=p->n) die("Trailing patch payload data");
}
static int cluster_offset(struct fat *f,uint32_t c,uint32_t *off) {
    if (c<2 || (uint64_t)f->data+(uint64_t)(c-2)*f->cluster+f->cluster>IMAGE_BYTES) return -1;
    *off=f->data+(c-2)*f->cluster;
    return 0;
}
static int next_cluster(struct fat *f,uint32_t c,uint32_t *next) {
    if ((uint64_t)c*2+2>f->fatbytes) return -1;
    *next=le16(f->image+f->fat+c*2);
    return 0;
}
static int fat_init(struct fat *f,const uint8_t *b) {
    if (b[510]!=0x55 || b[511]!=0xaa || le16(b+11)!=512) return -1;
    uint32_t spc=b[13],reserved=le16(b+14),nfats=b[16],spf=le16(b+22),entries=le16(b+17);
    if (!spc || (spc&(spc-1)) || spc>128 || !reserved || !nfats || nfats>2 || !spf || !entries) return -1;
    f->image=b;
    f->cluster=spc*512;
    f->fat=reserved*512;
    f->fatbytes=spf*512;
    f->root=f->fat+nfats*f->fatbytes;
    f->rootentries=entries;
    f->data=f->root+((entries*32+511)/512)*512;
    return f->data>=IMAGE_BYTES || f->root>=f->data || f->fat>=f->root ? -1:0;
}
static const uint8_t *search_entries(struct fat *f,uint32_t off,uint32_t count,const char *name,int *end) {
    if ((uint64_t)off+count*32>IMAGE_BYTES) return NULL;
    for (uint32_t i=0;i<count;i++) {
        const uint8_t *e=f->image+off+i*32;
        if (!e[0]) { *end=1; return NULL; }
        if (e[0]==0xe5 || e[11]==15 || (e[11]&8)) continue;
        char n[13];
        unsigned p=0;
        for (unsigned j=0;j<8 && e[j]!=' ';j++) n[p++]=(char)e[j];
        if (e[8]!=' ') {
            n[p++]='.';
            for (unsigned j=8;j<11 && e[j]!=' ';j++) n[p++]=(char)e[j];
        }
        n[p]=0;
        if (!strcmp(n,name)) return e;
    }
    return NULL;
}
static int locate(struct fat *f,struct file *a) {
    char path[65];
    strcpy(path,a->name);
    char *save=NULL,*part=strtok_r(path,"/",&save);
    uint32_t dir=0;
    const uint8_t *e=NULL;
    while (part) {
        int end=0;
        e=NULL;
        if (!dir) e=search_entries(f,f->root,f->rootentries,part,&end);
        else {
            uint32_t c=dir,n=0;
            while (c>=2 && c<0xfff8) {
                uint32_t off;
                if (++n>IMAGE_BYTES/f->cluster || cluster_offset(f,c,&off)) return -1;
                e=search_entries(f,off,f->cluster/32,part,&end);
                if (e || end) break;
                if (next_cluster(f,c,&c)) return -1;
            }
        }
        if (!e) return -1;
        part=strtok_r(NULL,"/",&save);
        if (part) {
            if (!(e[11]&16)) return -1;
            dir=le16(e+26);
            if (dir<2) return -1;
        }
    }
    if (!e || (e[11]&16) || le32(e+28)!=a->size) return -1;
    uint32_t start=le16(e+26),expected=start,count=0,c=start,off;
    while (c>=2 && c<0xfff8) {
        if (c!=expected++ || ++count>IMAGE_BYTES/f->cluster || cluster_offset(f,c,&off) || next_cluster(f,c,&c)) return -1;
    }
    if (c<0xfff8 || (uint64_t)count*f->cluster<a->size || cluster_offset(f,start,&a->offset)) return -1;
    return (uint64_t)a->offset+a->size>IMAGE_BYTES ? -1:0;
}
static const char *state_name(int state) {
    return state==STOCK?"STOCK":state==PATCHED?"PATCHED":state==MIXED?"MIXED":"UNKNOWN";
}
static int classify(struct payload *p,const uint8_t *image) {
    struct fat f;
    if (fat_init(&f,image)) return UNKNOWN;
    unsigned stock=0,patched=0;
    for (uint32_t i=0;i<p->count;i++) {
        struct file *a=&p->files[i];
        uint8_t h[32];
        if (locate(&f,a)) return UNKNOWN;
        sha_bytes(image+a->offset,a->size,h);
        if (!memcmp(h,a->stock,32)) stock++;
        else if (!memcmp(h,a->patched,32)) patched++;
        else return UNKNOWN;
    }
    return stock && patched ? MIXED : stock ? STOCK : PATCHED;
}
static int read_full(int fd,void *data,size_t n,off_t off) {
    size_t done=0;
    while (done<n) {
        ssize_t got=pread(fd,(uint8_t*)data+done,n-done,off+(off_t)done);
        if (got<0 && errno==EINTR) continue;
        if (got<=0) return -1;
        done+=(size_t)got;
    }
    return 0;
}
static int write_full(int fd,const void *data,size_t n,off_t off) {
    size_t done=0;
    while (done<n) {
        ssize_t got=pwrite(fd,(const uint8_t*)data+done,n-done,off+(off_t)done);
        if (got<0 && errno==EINTR) continue;
        if (got<=0) return -1;
        done+=(size_t)got;
    }
    return 0;
}
static int same_object(int a,int b) {
    struct stat x,y;
    return !fstat(a,&x) && !fstat(b,&y) && x.st_dev==y.st_dev && x.st_ino==y.st_ino && x.st_rdev==y.st_rdev;
}
/* Every fresh read, including every hash_fd(), passes through this barrier. */
static int flush_cache(int fd,int block) {
    if (fsync(fd)) return -1;
#ifdef D325_TESTING
    flush_calls++;
    const char *fail=getenv("D325_TEST_FAIL_FLUSH_AFTER");
    if (testing_fixture && fail && flush_calls==strtoul(fail,NULL,10)) {
        errno=EIO;
        return -1;
    }
#endif
    if (block) {
        if (ioctl(fd,BLKFLSBUF,0)) return -1;
        puts("CACHE_FLUSHED BLKFLSBUF");
    } else {
        int error=posix_fadvise(fd,0,0,POSIX_FADV_DONTNEED);
        if (error) { errno=error; return -1; }
        puts("CACHE_FLUSHED POSIX_FADV_DONTNEED");
    }
    return 0;
}
static int fresh_read(int fd,const char *path,int block,uint8_t *image,uint8_t out[32]) {
    if (flush_cache(fd,block)) return -1;
    int reader=fd;
    if (block) {
        reader=open(path,O_RDONLY|O_DIRECT|O_CLOEXEC|O_NOFOLLOW);
        if (reader<0) return -1;
        if (!same_object(fd,reader)) { close(reader); errno=ESTALE; return -1; }
        puts("READ_MODE O_DIRECT");
    }
    void *buffer=NULL;
    int error=posix_memalign(&buffer,4096,IO_BYTES);
    if (error) { if (block) close(reader); errno=error; return -1; }
    d325_sha256 hash;
    sha_init(&hash);
    int result=0;
    for (off_t off=0;off<IMAGE_BYTES;off+=IO_BYTES) {
        if (read_full(reader,buffer,IO_BYTES,off)) { result=-1; break; }
        sha_update(&hash,buffer,IO_BYTES);
        if (image) memcpy(image+off,buffer,IO_BYTES);
    }
    if (!result && out) sha_final(&hash,out);
    free(buffer);
    if (block) close(reader);
    return result;
}
static int hash_fd(int fd,const char *path,int block,uint8_t out[32]) {
    return fresh_read(fd,path,block,NULL,out);
}
static int write_changes(int fd,const uint8_t *before,const uint8_t *after,int rollback) {
    for (uint32_t off=0;off<IMAGE_BYTES;) {
        if (!memcmp(before+off,after+off,512)) { off+=512; continue; }
        uint32_t start=off;
        while (off<IMAGE_BYTES && off-start<IO_BYTES && memcmp(before+off,after+off,512)) off+=512;
        if (!rollback && interrupted) return -1;
        if (write_full(fd,after+start,off-start,start)) return -1;
        if (!rollback) changed_sectors+=(off-start)/512;
#ifdef D325_TESTING
        const char *fail=getenv("D325_TEST_FAIL_AFTER");
        if (!rollback && testing_fixture && fail && changed_sectors>=strtoul(fail,NULL,10)) return -1;
#endif
    }
    int result=fsync(fd);
#ifdef D325_TESTING
    if (!result && testing_fixture) printf("TEST_WRITE_PHASE %s\n",rollback?"ROLLBACK":"WRITE");
#endif
    return result;
}
static void hex_hash(const uint8_t h[32],char out[65]) {
    for (unsigned i=0;i<32;i++) sprintf(out+2*i,"%02x",h[i]);
}
static void print_hash(const char *label,const uint8_t h[32]) {
    char text[65]; hex_hash(h,text); printf("%s %s\n",label,text);
}
static int self_test(void) {
    uint8_t h[32];char text[65];
    sha_bytes("abc",3,h);hex_hash(h,text);
    if (strcmp(text,"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")) die("SHA-256 self-test failed");
    puts("PASS SHA256");return 0;
}
static int android_model_ok(void) {
    int fds[2];
    if (pipe(fds)) return 0;
    pid_t pid=fork();
    if (pid<0) { close(fds[0]);close(fds[1]);return 0; }
    if (!pid) {
        close(fds[0]);
        if (dup2(fds[1],STDOUT_FILENO)<0) _exit(127);
        close(fds[1]);execl("/system/bin/getprop","getprop","ro.product.model",(char*)NULL);_exit(127);
    }
    close(fds[1]);char name[128];ssize_t used=0,n;
    while (used<(ssize_t)sizeof(name)-1 && (n=read(fds[0],name+used,sizeof(name)-1-used))>0) used+=n;
    close(fds[0]);int status;
    while (waitpid(pid,&status,0)<0) { if (errno!=EINTR) return 0; }
    name[used]=0;
    return WIFEXITED(status) && !WEXITSTATUS(status) && strstr(name,"D325");
}
static int regular_image(const char *path) {
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    struct stat st;
    if (fd<0) die("Cannot open dump or image file");
    if (fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size!=IMAGE_BYTES) die("Dump/image must be a 64 MiB regular file");
    return fd;
}
static void verify_sidecar(const char *path,const uint8_t hash[32]) {
    char side[PATH_MAX],expected[65],line[PATH_MAX+100];
    if (snprintf(side,sizeof(side),"%s.sha256",path)>=(int)sizeof(side)) die("Dump path is too long");
    int fd=open(side,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) { if (errno==ENOENT) return; die("Cannot open dump checksum"); }
    ssize_t n=read(fd,line,sizeof(line)-1);close(fd);
    if (n<64) die("Invalid dump checksum file");
    line[n]=0;hex_hash(hash,expected);
    if (strncasecmp(line,expected,64) || (line[64] && !isspace((unsigned char)line[64]))) die("Dump SHA-256 does not match its checksum file");
}
static void parent_sync(const char *path) {
    char dir[PATH_MAX];
    if (strlen(path)>=sizeof(dir)) die("Dump path is too long");
    strcpy(dir,path);char *slash=strrchr(dir,'/');
    if (!slash) strcpy(dir,".");else if (slash==dir) slash[1]=0;else *slash=0;
    int fd=open(dir,O_RDONLY|O_DIRECTORY|O_CLOEXEC);
    if (fd<0 || fsync(fd)) die("Cannot sync dump directory");
    close(fd);
}
static void save_dump(int fd,const char *path,int block,const char *output,const uint8_t *first,const uint8_t first_hash[32],int state) {
    uint8_t *second=malloc(IMAGE_BYTES),check[32];
    if (!second || fresh_read(fd,path,block,second,check)) die("Cannot read the partition a second time");
    if (memcmp(first,second,IMAGE_BYTES) || memcmp(first_hash,check,32)) die("Partition reads differ; no dump saved");
    free(second);
    char final[PATH_MAX],side[PATH_MAX],temp[PATH_MAX],side_temp[PATH_MAX],hex[65];
    hex_hash(first_hash,hex);struct stat st;
    int directory=!strcmp(output,DEFAULT_DUMPS) || (strlen(output) && output[strlen(output)-1]=='/');
    if (!stat(output,&st) && S_ISDIR(st.st_mode)) directory=1;
    if (directory) {
        if (mkdir(output,0755) && errno!=EEXIST) die("Cannot create dump directory");
        char lower[16];snprintf(lower,sizeof(lower),"%s",state_name(state));
        for (char *p=lower;*p;p++) *p=(char)tolower((unsigned char)*p);
        if (snprintf(final,sizeof(final),"%s/modem-dump-%s-%.8s.bin",output,lower,hex)>=(int)sizeof(final)) die("Dump path is too long");
    } else {
        if (strlen(output)>=sizeof(final)) die("Dump path is too long");
        strcpy(final,output);
    }
    if (snprintf(side,sizeof(side),"%s.sha256",final)>=(int)sizeof(side) ||
        snprintf(temp,sizeof(temp),"%s.part-XXXXXX",final)>=(int)sizeof(temp) ||
        snprintf(side_temp,sizeof(side_temp),"%s.part-XXXXXX",side)>=(int)sizeof(side_temp)) die("Dump path is too long");
    if (!lstat(final,&st) || errno!=ENOENT) die("Dump already exists or cannot be checked; nothing overwritten");
    if (!lstat(side,&st) || errno!=ENOENT) die("Dump checksum already exists or cannot be checked; nothing overwritten");
    int writer=mkstemp(temp);
    if (writer<0) die("Cannot create a temporary dump file");
    if (write_full(writer,first,IMAGE_BYTES,0) || hash_fd(writer,temp,0,check) || memcmp(first_hash,check,32) || fchmod(writer,0644)) {
        close(writer);unlink(temp);die("Dump file write/readback failed");
    }
    close(writer);
    int checksum=mkstemp(side_temp);
    if (checksum<0) { unlink(temp);die("Cannot create dump checksum"); }
    char line[PATH_MAX+100];const char *base=strrchr(final,'/');base=base?base+1:final;
    int n=snprintf(line,sizeof(line),"%s  %s\n",hex,base);
    if (n<0 || n>=(int)sizeof(line) || write_full(checksum,line,(size_t)n,0) || fsync(checksum) || fchmod(checksum,0644)) {
        close(checksum);unlink(temp);unlink(side_temp);die("Cannot write dump checksum");
    }
    close(checksum);
    /* link() publishes complete files atomically and refuses existing names. */
    if (link(temp,final)) { unlink(temp);unlink(side_temp);die("Cannot publish dump; existing files were not overwritten"); }
    if (link(side_temp,side)) { unlink(final);unlink(temp);unlink(side_temp);die("Cannot publish dump checksum"); }
    unlink(temp);unlink(side_temp);parent_sync(final);
    printf("DUMP_PATH %s\n",final);printf("DUMP_STATE %s\n",state_name(state));print_hash("DUMP_SHA256",first_hash);puts("DUMP_VERIFIED");
}
int main(int argc,char **argv) {
    const char *path=NULL,*payload_path=NULL,*dump_path=NULL,*restore_path=NULL;
    int block=0,allow=0,dry=0,mode=0,android_root=0;
    for (int i=1;i<argc;i++) {
        if (!strcmp(argv[i],"--self-test")) return self_test();
        else if ((!strcmp(argv[i],"--image") || !strcmp(argv[i],"--block")) && i+1<argc) { block=!strcmp(argv[i],"--block");path=argv[++i]; }
        else if (!strcmp(argv[i],"--payload") && i+1<argc) payload_path=argv[++i];
        else if (!strcmp(argv[i],"--dump") && i+1<argc) { if (mode) die("Choose one operation");mode=4;dump_path=argv[++i]; }
        else if (!strcmp(argv[i],"--restore-dump") && i+1<argc) { if (mode) die("Choose one operation");mode=5;restore_path=argv[++i]; }
        else if (!strcmp(argv[i],"--toggle") || !strcmp(argv[i],"--apply") || !strcmp(argv[i],"--revert")) {
            if (mode) die("Choose one operation");
            mode=!strcmp(argv[i],"--toggle")?1:!strcmp(argv[i],"--apply")?2:3;
        } else if (!strcmp(argv[i],"--allow-block-write")) allow=1;
        else if (!strcmp(argv[i],"--dry-run")) dry=1;
        else if (!strcmp(argv[i],"--android-root")) android_root=1;
        else die("Usage: modem-toggle --image FILE|--block DEVICE --payload FILE [--toggle|--apply|--revert|--dump FILE_OR_DIR|--restore-dump FILE] [--dry-run]");
    }
    if (!path || !payload_path) die("Image/device and payload are required");
    if (mode==4 && dry) die("--dry-run is not supported with --dump");
    setbuf(stdout,NULL);atexit(cleanup);signal(SIGINT,stop_signal);signal(SIGTERM,stop_signal);
#ifdef D325_TESTING
    testing_fixture=!block;
#endif
    struct payload patch={0};load_payload(&patch,payload_path);
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    struct stat st;
    if (fd<0 || fstat(fd,&st)) die("Cannot open/stat selected target");
    if (block) {
        uint64_t size=0;
        if (!S_ISBLK(st.st_mode) || ioctl(fd,BLKGETSIZE64,&size) || size!=IMAGE_BYTES) die("Selected block device is not a 64 MiB modem partition");
    } else if (!S_ISREG(st.st_mode) || st.st_size!=IMAGE_BYTES) die("Fixture must be a 64 MiB regular file");
    if (flock(fd,(mode && mode!=4 ? LOCK_EX:LOCK_SH)|LOCK_NB)) die("Another modem operation is in progress");
    uint8_t *source=malloc(IMAGE_BYTES),*target=NULL,old_hash[32],new_hash[32],check[32];
    if (!source || fresh_read(fd,path,block,source,old_hash)) die("Cannot read a fresh complete image (cache flush/direct read failed)");
    int state=classify(&patch,source);printf("STATE %s\n",state_name(state));print_hash("SOURCE_SHA256",old_hash);
    if (mode==4) { save_dump(fd,path,block,dump_path,source,old_hash,state);close(fd);return 0; }
    if (!mode) { close(fd);return 0; }
    int desired;
    if (mode==5) {
        target=malloc(IMAGE_BYTES);int input=regular_image(restore_path);
        if (!target || fresh_read(input,restore_path,0,target,new_hash)) die("Cannot read dump");
        verify_sidecar(restore_path,new_hash);close(input);
        desired=classify(&patch,target);
        if (desired!=STOCK && desired!=PATCHED) die("Dump is not a supported STOCK or PATCHED image; nothing written");
        printf("RESTORE_DUMP_STATE %s\n",state_name(desired));
    } else {
        if (state==UNKNOWN || state==MIXED) die("Unsupported or mixed partition; use --restore-dump with a verified dump");
        if ((mode==2 && state==PATCHED) || (mode==3 && state==STOCK)) { puts("ALREADY_IN_REQUESTED_STATE");close(fd);return 0; }
        desired=state==STOCK?PATCHED:STOCK;
        target=malloc(IMAGE_BYTES);
        if (!target) die("Cannot allocate target image");
        memcpy(target,source,IMAGE_BYTES);
        for (uint32_t i=0;i<patch.count;i++) {
            struct file *f=&patch.files[i];
            for (uint32_t j=0;j<f->nruns;j++) {
                struct run *r=&f->runs[j];
                for (uint32_t k=0;k<r->n;k++) target[f->offset+r->at+k]^=r->mask[k];
            }
        }
        if (classify(&patch,target)!=desired) die("Target firmware hashes mismatch");
        sha_bytes(target,IMAGE_BYTES,new_hash);
    }
    print_hash("TARGET_SHA256",new_hash);printf("DIRECTION %s_TO_%s\n",state_name(state),state_name(desired));
    if (dry) { puts("DRY_RUN_NO_WRITES");close(fd);return 0; }
    if (block) {
        if (!allow || getuid()!=0 || (access("/twres",F_OK) && !(android_root && android_model_ok()))) die("Block writes require TWRP or explicit Android root on LG D325");
        ro_fd=fd;
        if (ioctl(fd,BLKROGET,&original_ro)) die("Cannot read modem read-only flag");
        if (original_ro) { int rw=0;if (ioctl(fd,BLKROSET,&rw)) die("Cannot temporarily make modem writable"); }
    }
    int writer=open(path,O_RDWR|O_CLOEXEC|O_NOFOLLOW);
    if (writer<0 || !same_object(fd,writer)) die("Cannot open target for writing or target identity changed");
    if (hash_fd(writer,path,block,check) || memcmp(check,old_hash,32)) die("Target changed or fresh-read barrier failed; nothing written");
    if (write_changes(writer,source,target,0) || hash_fd(writer,path,block,check) || memcmp(check,new_hash,32)) {
        fprintf(stderr,"Write/verification failed; restoring original changed sectors\n");
#ifdef D325_TESTING
        if (testing_fixture && getenv("D325_TEST_DISABLE_ROLLBACK")) {
            fsync(writer);puts("TEST_INTERRUPTED_NO_ROLLBACK");close(writer);close(fd);return 4;
        }
#endif
        if (write_changes(writer,target,source,1) || hash_fd(writer,path,block,check) || memcmp(check,old_hash,32)) die("Rollback failed; restore a known dump from recovery before rebooting");
        puts("ROLLBACK_VERIFIED");close(writer);if (!block) close(fd);return 3;
    }
    printf("CHANGED_SECTORS %u\n",changed_sectors);printf("RESULT %s\n",state_name(desired));puts("READBACK_VERIFIED");
    close(writer);if (!block) close(fd);return 0;
}
