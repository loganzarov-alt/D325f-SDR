#define _GNU_SOURCE
#define _FILE_OFFSET_BITS 64
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include "sha256.h"

/* Linux block-device ioctl ABI; no kernel runtime is bundled. */
#define BLKROSET _IO(0x12,93)
#define BLKROGET _IO(0x12,94)
#define BLKGETSIZE64 _IOR(0x12,114,size_t)

#define IMAGE_BYTES (64u*1024u*1024u)
struct run {uint32_t at,n;const uint8_t *mask;};
struct file {char name[65];uint32_t size,nruns,offset;uint8_t stock[32],patched[32];struct run *runs;};
struct payload {uint8_t *bytes;size_t n,pos;uint32_t count;struct file files[16];};
struct fat {const uint8_t *image;uint32_t cluster,fat,fatbytes,root,rootentries,data;};
static volatile sig_atomic_t interrupted;
static int original_ro=-1,ro_fd=-1;
static void stop_signal(int sig){(void)sig;interrupted=1;}
static void cleanup(void){if(ro_fd>=0){if(original_ro==1)ioctl(ro_fd,BLKROSET,&original_ro);close(ro_fd);}}
static void die(const char *text){fprintf(stderr,"ERROR: %s\n",text);exit(2);}
static uint16_t le16(const uint8_t *p){return(uint16_t)p[0]|(uint16_t)p[1]<<8;}
static uint32_t le32(const uint8_t *p){return(uint32_t)p[0]|(uint32_t)p[1]<<8|(uint32_t)p[2]<<16|(uint32_t)p[3]<<24;}
static const uint8_t *take(struct payload *p,size_t n){if(n>p->n-p->pos)die("Truncated patch payload");const uint8_t *r=p->bytes+p->pos;p->pos+=n;return r;}
static uint32_t number(struct payload *p){return le32(take(p,4));}
static void load_payload(struct payload *p,const char *path){
 FILE *f=fopen(path,"rb");if(!f)die("Cannot open patch payload");
 if(fseek(f,0,SEEK_END))die("Payload seek");long n=ftell(f);if(n<12||n>1048576)die("Payload length");rewind(f);
 p->bytes=malloc((size_t)n);p->n=(size_t)n;p->pos=0;if(!p->bytes||fread(p->bytes,1,p->n,f)!=p->n)die("Payload read");fclose(f);
 if(memcmp(take(p,8),"D325PT03",8))die("Patch format");p->count=number(p);if(!p->count||p->count>16)die("Patch file count");
 for(uint32_t i=0;i<p->count;i++){
  struct file *a=&p->files[i];uint32_t len=number(p);if(!len||len>64)die("Patch name length");memcpy(a->name,take(p,len),len);a->name[len]=0;
  if(strncmp(a->name,"IMAGE/MODEM.",12)||strstr(a->name,".."))die("Unexpected firmware path");
  a->size=number(p);if(!a->size||a->size>IMAGE_BYTES)die("Firmware size");memcpy(a->stock,take(p,32),32);memcpy(a->patched,take(p,32),32);
  if(!memcmp(a->stock,a->patched,32))die("Ambiguous patch hashes");a->nruns=number(p);if(!a->nruns||a->nruns>4096)die("Patch run count");
  a->runs=calloc(a->nruns,sizeof(*a->runs));if(!a->runs)die("Patch allocation");uint32_t end=0;
  for(uint32_t j=0;j<a->nruns;j++){struct run *r=&a->runs[j];r->at=number(p);r->n=number(p);if(!r->n||r->at<end||r->n>a->size||r->at>a->size-r->n)die("Patch run bounds/overlap");r->mask=take(p,r->n);end=r->at+r->n;}
 }
 if(p->pos!=p->n)die("Trailing patch payload data");
}
static uint32_t cluster_offset(struct fat *f,uint32_t c){
 if(c<2||(uint64_t)f->data+(uint64_t)(c-2)*f->cluster+f->cluster>IMAGE_BYTES)die("FAT cluster bounds");return f->data+(c-2)*f->cluster;
}
static uint32_t next_cluster(struct fat *f,uint32_t c){if((uint64_t)c*2+2>f->fatbytes)die("FAT index bounds");return le16(f->image+f->fat+c*2);}
static void fat_init(struct fat *f,const uint8_t *b){
 if(b[510]!=0x55||b[511]!=0xaa||le16(b+11)!=512)die("Not a supported modem FAT image");
 uint32_t spc=b[13],reserved=le16(b+14),nfats=b[16],spf=le16(b+22),entries=le16(b+17);
 if(!spc||(spc&(spc-1))||spc>128||!reserved||!nfats||nfats>2||!spf||!entries)die("Unsupported FAT geometry");
 f->image=b;f->cluster=spc*512;f->fat=reserved*512;f->fatbytes=spf*512;f->root=f->fat+nfats*f->fatbytes;f->rootentries=entries;f->data=f->root+((entries*32+511)/512)*512;
 if(f->data>=IMAGE_BYTES||f->root>=f->data||f->fat>=f->root)die("FAT geometry bounds");
}
static const uint8_t *search_entries(struct fat *f,uint32_t off,uint32_t count,const char *name,int *end){
 if((uint64_t)off+count*32>IMAGE_BYTES)die("Directory bounds");
 for(uint32_t i=0;i<count;i++){
  const uint8_t *e=f->image+off+i*32;if(!e[0]){*end=1;return NULL;}
  if(e[0]==0xe5||e[11]==15||(e[11]&8))continue;
  char n[13];unsigned p=0;for(unsigned j=0;j<8&&e[j]!=' ';j++)n[p++]=(char)e[j];
  if(e[8]!=' '){n[p++]='.';for(unsigned j=8;j<11&&e[j]!=' ';j++)n[p++]=(char)e[j];}n[p]=0;
  if(!strcmp(n,name))return e;
 }
 return NULL;
}
static void locate(struct fat *f,struct file *a){
 char path[65];strcpy(path,a->name);char *save=NULL,*part=strtok_r(path,"/",&save);uint32_t dir=0;const uint8_t *e=NULL;
 while(part){
  int end=0;e=NULL;
  if(!dir)e=search_entries(f,f->root,f->rootentries,part,&end);
  else for(uint32_t c=dir,n=0;c>=2&&c<0xfff8;c=next_cluster(f,c)){
   if(++n>IMAGE_BYTES/f->cluster)die("Directory FAT loop");e=search_entries(f,cluster_offset(f,c),f->cluster/32,part,&end);if(e||end)break;
  }
  if(!e)die("Required firmware file is missing");part=strtok_r(NULL,"/",&save);
  if(part){if(!(e[11]&16))die("Firmware directory mismatch");dir=le16(e+26);if(dir<2)die("Firmware directory cluster");}
 }
 if((e[11]&16)||le32(e+28)!=a->size)die("Firmware file size mismatch");
 uint32_t start=le16(e+26),expected=start,count=0,c=start;
 while(c>=2&&c<0xfff8){if(c!=expected++||++count>IMAGE_BYTES/f->cluster)die("Fragmented/corrupt firmware file is not supported");cluster_offset(f,c);c=next_cluster(f,c);}
 if(c<0xfff8||(uint64_t)count*f->cluster<a->size)die("Truncated FAT file");a->offset=cluster_offset(f,start);if((uint64_t)a->offset+a->size>IMAGE_BYTES)die("Firmware data bounds");
}
enum {STOCK=1,PATCHED=2,MIXED=3,UNKNOWN=4};
static const char *state_name(int state){return state==STOCK?"STOCK":state==PATCHED?"PATCHED":state==MIXED?"MIXED":"UNKNOWN";}
static int classify(struct payload *p,const uint8_t *image){
 struct fat f;fat_init(&f,image);unsigned stock=0,patched=0,unknown=0;
 for(uint32_t i=0;i<p->count;i++){
  struct file *a=&p->files[i];locate(&f,a);uint8_t h[32];sha_bytes(image+a->offset,a->size,h);
  if(!memcmp(h,a->stock,32))stock++;else if(!memcmp(h,a->patched,32))patched++;else{unknown++;fprintf(stderr,"Unsupported firmware file: %s\n",a->name);}
 }
 return unknown?UNKNOWN:(stock&&patched)?MIXED:stock?STOCK:PATCHED;
}
static int read_full(int fd,void *data,size_t n,off_t off){
 size_t done=0;while(done<n){ssize_t got=pread(fd,(uint8_t*)data+done,n-done,off+(off_t)done);if(got<0&&errno==EINTR)continue;if(got<=0)return -1;done+=(size_t)got;}return 0;
}
static int write_full(int fd,const void *data,size_t n,off_t off){
 size_t done=0;while(done<n){ssize_t got=pwrite(fd,(const uint8_t*)data+done,n-done,off+(off_t)done);if(got<0&&errno==EINTR)continue;if(got<=0)return -1;done+=(size_t)got;}return 0;
}
static int hash_fd(int fd,uint8_t out[32]){
 uint8_t buf[65536];d325_sha256 s;sha_init(&s);
 for(off_t off=0;off<IMAGE_BYTES;off+=sizeof(buf)){if(read_full(fd,buf,sizeof(buf),off))return -1;sha_update(&s,buf,sizeof(buf));}sha_final(&s,out);return 0;
}
static unsigned changed_sectors;
static int write_changes(int fd,const uint8_t *before,const uint8_t *after,int rollback){
 for(uint32_t off=0;off<IMAGE_BYTES;){
  if(!memcmp(before+off,after+off,512)){off+=512;continue;}
  uint32_t start=off;while(off<IMAGE_BYTES&&off-start<65536&&memcmp(before+off,after+off,512))off+=512;
  if(!rollback&&interrupted)return -1;
  if(write_full(fd,after+start,off-start,start))return -1;
  if(!rollback)changed_sectors+=(off-start)/512;
#ifdef D325_TESTING
  /* Test builds only: exercise rollback on regular fixture files. */
  if(!rollback&&getenv("D325_TEST_FAIL_AFTER")&&changed_sectors>=strtoul(getenv("D325_TEST_FAIL_AFTER"),NULL,10))return -1;
#endif
 }
 return fsync(fd);
}
static void print_hash(const char *label,const uint8_t h[32]){printf("%s ",label);for(unsigned i=0;i<32;i++)printf("%02x",h[i]);putchar('\n');}
static unsigned temperature(void){
 unsigned seen=0,max=0;
 for(unsigned i=0;i<2;i++){
  char path[100];snprintf(path,sizeof(path),"/sys/class/thermal/thermal_zone%u/temp",i);FILE *f=fopen(path,"r");unsigned t;
  if(!f)continue;int ok=fscanf(f,"%u",&t);fclose(f);if(ok!=1)continue;if(t>1000)t/=1000;if(t>max)max=t;seen++;
 }
 if(seen!=2)die("Cannot verify both CPU temperature sensors");if(max>=58)die("CPU temperature is 58 C or higher; cool the phone before flashing");printf("CPU_C %u\n",max);return max;
}
static int self_test(void){
 const char *expected="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
 uint8_t h[32];sha_bytes("abc",3,h);char out[65];for(unsigned i=0;i<32;i++)sprintf(out+2*i,"%02x",h[i]);
 if(strcmp(out,expected))die("SHA-256 self-test failed");puts("PASS SHA256");return 0;
}
static int android_model_ok(void){
 int fds[2];if(pipe(fds))return 0;pid_t pid=fork();if(pid<0){close(fds[0]);close(fds[1]);return 0;}
 if(!pid){close(fds[0]);if(dup2(fds[1],STDOUT_FILENO)<0)_exit(127);close(fds[1]);execl("/system/bin/getprop","getprop","ro.product.model",(char*)NULL);_exit(127);}
 close(fds[1]);char name[128];ssize_t used=0,n;while(used<(ssize_t)sizeof(name)-1&&(n=read(fds[0],name+used,sizeof(name)-1-used))>0)used+=n;
 close(fds[0]);int status;while(waitpid(pid,&status,0)<0){if(errno!=EINTR)return 0;}name[used]=0;
 return WIFEXITED(status)&&!WEXITSTATUS(status)&&strstr(name,"D325");
}
int main(int argc,char **argv){
 const char *path=NULL,*payload_path=NULL;int block=0,allow=0,dry=0,mode=0,android_root=0;
 for(int i=1;i<argc;i++){
  if(!strcmp(argv[i],"--self-test"))return self_test();
  else if((!strcmp(argv[i],"--image")||!strcmp(argv[i],"--block"))&&i+1<argc){block=!strcmp(argv[i],"--block");path=argv[++i];}
  else if(!strcmp(argv[i],"--payload")&&i+1<argc)payload_path=argv[++i];
  else if(!strcmp(argv[i],"--toggle"))mode=1;else if(!strcmp(argv[i],"--apply"))mode=2;else if(!strcmp(argv[i],"--revert"))mode=3;
  else if(!strcmp(argv[i],"--allow-block-write"))allow=1;else if(!strcmp(argv[i],"--dry-run"))dry=1;
  else if(!strcmp(argv[i],"--android-root"))android_root=1;
  else die("Usage: modem-toggle --image FILE|--block DEVICE --payload FILE [--toggle|--apply|--revert] [--dry-run]");
 }
 if(!path||!payload_path)die("Image/device and payload are required");
 setbuf(stdout,NULL);atexit(cleanup);signal(SIGINT,stop_signal);signal(SIGTERM,stop_signal);
 if(block&&mode&&temperature()>=48)die("Cool the phone below 48 C before starting");
 struct payload patch={0};load_payload(&patch,payload_path);
 int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);if(fd<0)die("Cannot open selected target");struct stat st;
 if(fstat(fd,&st))die("Cannot stat selected target");
 if(block){uint64_t size=0;if(!S_ISBLK(st.st_mode)||ioctl(fd,BLKGETSIZE64,&size)||size!=IMAGE_BYTES)die("Selected block device is not a 64 MiB modem partition");}
 else if(!S_ISREG(st.st_mode)||st.st_size!=IMAGE_BYTES)die("Fixture must be a 64 MiB regular file");
 uint8_t *source=malloc(IMAGE_BYTES),*target=NULL;if(!source||read_full(fd,source,IMAGE_BYTES,0))die("Cannot read complete image");
 int state=classify(&patch,source);printf("STATE %s\n",state_name(state));
 if(state==UNKNOWN||state==MIXED)die("Firmware version is unsupported or patch state is mixed; nothing written");
 if(!mode){close(fd);return 0;}
 if((mode==2&&state==PATCHED)||(mode==3&&state==STOCK)){puts("ALREADY_IN_REQUESTED_STATE");close(fd);return 0;}
 int desired=state==STOCK?PATCHED:STOCK;target=malloc(IMAGE_BYTES);if(!target)die("Target allocation");memcpy(target,source,IMAGE_BYTES);
 for(uint32_t i=0;i<patch.count;i++){
  struct file *f=&patch.files[i];for(uint32_t j=0;j<f->nruns;j++){
   struct run *r=&f->runs[j];for(uint32_t k=0;k<r->n;k++)target[f->offset+r->at+k]^=r->mask[k];
  }
 }
 if(classify(&patch,target)!=desired)die("Target firmware hashes mismatch");uint8_t old_hash[32],new_hash[32],check[32];
 sha_bytes(source,IMAGE_BYTES,old_hash);sha_bytes(target,IMAGE_BYTES,new_hash);print_hash("SOURCE_SHA256",old_hash);print_hash("TARGET_SHA256",new_hash);
 printf("DIRECTION %s_TO_%s\n",state_name(state),state_name(desired));
 if(dry){puts("DRY_RUN_NO_WRITES");close(fd);return 0;}
 if(block){
  if(!allow||getuid()!=0||(access("/twres",F_OK)&&!(android_root&&android_model_ok())))die("Block writes require TWRP or explicit Android root on LG D325");temperature();
  ro_fd=fd;if(ioctl(fd,BLKROGET,&original_ro))die("Cannot read modem read-only flag");
  if(original_ro){int rw=0;if(ioctl(fd,BLKROSET,&rw))die("Cannot temporarily make modem writable");}
 }
 int writer=open(path,O_RDWR|O_CLOEXEC|O_NOFOLLOW);if(writer<0)die("Cannot open target for writing");struct stat after;
 if(fstat(writer,&after)||after.st_dev!=st.st_dev||after.st_ino!=st.st_ino||after.st_rdev!=st.st_rdev)die("Target identity changed");
 if(hash_fd(writer,check)||memcmp(check,old_hash,32))die("Target changed since validation; nothing written");
 if(block)temperature();
 if(write_changes(writer,source,target,0)||hash_fd(writer,check)||memcmp(check,new_hash,32)){
  fprintf(stderr,"Write/verification failed; restoring original changed sectors\n");
  if(write_changes(writer,target,source,1)||hash_fd(writer,check)||memcmp(check,old_hash,32))die("Rollback failed; do not reboot, restore a known image from recovery");
  puts("ROLLBACK_VERIFIED");close(writer);if(!block)close(fd);return 3;
 }
 printf("CHANGED_SECTORS %u\n",changed_sectors);printf("RESULT %s\n",state_name(desired));puts("READBACK_VERIFIED");
 close(writer);if(!block)close(fd);return 0;
}
