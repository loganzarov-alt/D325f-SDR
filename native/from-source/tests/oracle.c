// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#define _GNU_SOURCE
#include <assert.h>
#include <elf.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

/* Only pure functions and mocked radio-control logic are executed here.
 * No /dev/mem, /dev/diag, transmitter, CPU control or firmware write is used. */
struct radio_config;
static int transaction_hook(int,unsigned,unsigned,unsigned);
static int frequency_control_hook(int,unsigned,unsigned,unsigned);
static int ftm_word_hook(int,unsigned,unsigned short);
static int wait_pll_hook(int,unsigned *);
static int apply_gain_controls_hook(int,const struct radio_config *);
#define LG_STREAM_TEST 1
#include "../build/oracle-source.c"

static unsigned char *image;
static int oracle_side,steps,fail_step,short_step,bad_step;
static int wait_error,gain_error;
static const struct radio_config *config_now;
struct event {unsigned kind,a,b,c;};
static struct event events[64];
static unsigned tests;

static void checked(int ok,const char *message){
 if(!ok){printf("FAIL %s at case %u\n",message,tests);exit(1);}
}
static void jump(unsigned address,void *target){
 uint32_t *p=(uint32_t*)(image+address);
 p[0]=0xe51ff004;p[1]=(uintptr_t)target;
 __builtin___clear_cache((char*)p,(char*)(p+2));
}
static void load_image(const char *path){
 FILE *f=fopen(path,"rb");checked(f!=NULL,"open ELF");
 Elf32_Ehdr eh;checked(fread(&eh,1,sizeof(eh),f)==sizeof(eh),"ELF header");
 checked(!memcmp(eh.e_ident,ELFMAG,SELFMAG)&&eh.e_machine==EM_ARM,"ARM ELF");
 image=mmap((void*)0x02000000,0x200000,PROT_READ|PROT_WRITE|PROT_EXEC,
            MAP_PRIVATE|MAP_ANONYMOUS|MAP_FIXED,-1,0);
 checked(image!=(void*)-1,"map oracle");
 for(unsigned i=0;i<eh.e_phnum;i++){
  Elf32_Phdr ph;fseek(f,eh.e_phoff+i*eh.e_phentsize,SEEK_SET);
  checked(fread(&ph,1,sizeof(ph),f)==sizeof(ph),"ELF phdr");
  if(ph.p_type!=PT_LOAD)continue;
  checked(ph.p_vaddr+ph.p_memsz<=0x200000,"ELF segment bounds");
  fseek(f,ph.p_offset,SEEK_SET);
  checked(fread(image+ph.p_vaddr,1,ph.p_filesz,f)==ph.p_filesz,"ELF segment");
 }
 fclose(f);__builtin___clear_cache((char*)image,(char*)image+0x200000);
 jump(0x11280,transaction_hook);
 jump(0x12630,frequency_control_hook);
 jump(0x12b00,ftm_word_hook);
 jump(0x129f4,wait_pll_hook);
 jump(0x12ab0,apply_gain_controls_hook);
}
static int reply(unsigned kind,unsigned a,unsigned b,unsigned c){
 checked(steps<64,"event capacity");events[steps++]=(struct event){kind,a,b,c};
 u8 *ans=oracle_side?image+0x32dbc:answer;
 unsigned *len=oracle_side?(unsigned*)(image+0x34dbc):&answerlen;
 memset(ans,0,64);*len=48;
 if(steps==fail_step)return -1;
 ans[7]=2;
 if(kind==2){
  if(config_now->mode==1){
   unsigned wanted=(unsigned)config_now->uarfcn*200000u+(config_now->band==8?385000000u:0);
   put32(ans+24,0x90f804d1);put32(ans+32,wanted);put32(ans+40,wanted);
   if(a==4){put32(ans+16,config_now->band);put32(ans+20,config_now->uarfcn);}
  }else{
   put32(ans+16,0x485a4433);put32(ans+28,1);put32(ans+40,config_now->lo);
  }
 }
 /* A PLL byte is meaningful only in a transaction(262) reply. */
 if(!(kind==1&&a==262))ans[7]=0;
 if(steps==short_step)*len=0;
 if(steps==bad_step)ans[6]=1;
 return 1;
}
static int transaction_hook(int fd,unsigned cmd,unsigned a,unsigned b){
 (void)fd;return reply(1,cmd,a,b);
}
static int frequency_control_hook(int fd,unsigned op,unsigned a,unsigned b){
 (void)fd;return reply(2,op,a,b);
}
static int ftm_word_hook(int fd,unsigned cmd,unsigned short value){
 (void)fd;return reply(3,cmd,value,0);
}
static int wait_pll_hook(int fd,unsigned *pll){
 (void)fd;*pll=wait_error?0:2;return wait_error;
}
static int apply_gain_controls_hook(int fd,const struct radio_config *c){
 (void)fd;(void)c;return gain_error;
}
static unsigned rng=0x12345678;
static unsigned random32(void){rng^=rng<<13;rng^=rng>>17;rng^=rng<<5;return rng;}

static void test_decode(void){
 int (*native)(const u8*,struct radio_config*)=(void*)(image+0x118c4);
 for(unsigned i=0;i<2500;i++){
  u8 p[24];for(unsigned j=0;j<24;j++)p[j]=random32();
  put32(p+8,0x3243464c);put16(p+18,0x4c47);
  p[12]=i%12;p[13]=(u8[]){10,20,40,0,30}[i%5];p[14]=i%7;
  p[15]=(u8[]){1,8,0}[i%3];p[22]=i%3;
  if(i%4==0){put32(p,0);put32(p+4,0);}
  if(i%17==0)p[8]=0;if(i%19==0)p[18]=0;
  struct radio_config a,b;memset(&a,0xa5,sizeof(a));memset(&b,0xa5,sizeof(b));
  int ra=native(p,&a),rb=decode(p,&b);tests++;
  checked(ra==rb&&!memcmp(&a,&b,sizeof(a)),"decode differs");
 }
 puts("PASS decode: 2500 binary/C cases");
}

static void test_feed(void){
 int (*native)(u8,unsigned)=(void*)(image+0x11660);
 for(unsigned i=0;i<800;i++){
  unsigned command=i%2?262:902,n=8+random32()%200;
  u8 raw[256],wire[520];unsigned w=0;
  for(unsigned j=0;j<n;j++)raw[j]=(u8)random32();
  raw[0]=75;raw[1]=11;put16(raw+2,1);put16(raw+4,command);
  if(i%7==0)raw[0]=0x13;
  put16(raw+n,crc(raw,n));n+=2;
  if(i%5==0)raw[n-1]^=1;
  for(unsigned j=0;j<n;j++){
   if(raw[j]==0x7e||raw[j]==0x7d){wire[w++]=0x7d;wire[w++]=raw[j]^32;}
   else wire[w++]=raw[j];
  }
  wire[w++]=0x7e;
  memset(image+0x32dbc,0,8192);memset(answer,0,sizeof(answer));
  *(u32*)(image+0x34dbc)=*(u32*)(image+0x34dc0)=*(u32*)(image+0x34dc4)=0;
  packetlen=escaped=answerlen=0;
  for(unsigned j=0;j<w;j++){
   int a=native(wire[j],command),b=feed(wire[j],command);
   checked(a==b,"HDLC result differs");
   checked(*(u32*)(image+0x34dc0)==packetlen,"HDLC length differs");
   checked(*(u8*)(image+0x34dc4)==escaped,"HDLC escape differs");
  }
  tests++;
  checked(*(u32*)(image+0x34dbc)==answerlen,"HDLC answer length differs");
  checked(!memcmp(image+0x32dbc,answer,answerlen),"HDLC payload differs");
 }
 puts("PASS HDLC: 800 binary/C frames incl. CRC, escaping and DIAG errors");
}

static void test_consume(void){
 int (*native)(struct ci_ring*,struct stage_ring*,u32*,u32*)=(void*)(image+0x12510);
 struct ci_ring *ring=calloc(1,sizeof(*ring));
 struct stage_ring *a=mmap(NULL,STAGE_MAP_BYTES,3,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 struct stage_ring *b=mmap(NULL,STAGE_MAP_BYTES,3,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
 checked(ring&&a!=(void*)-1&&b!=(void*)-1,"queue allocation");
 for(unsigned i=0;i<RING_DATA_BYTES;i++)ring->data[i]=(u8)random32();
 for(unsigned i=0;i<300;i++){
  unsigned av=(random32()%(RING_DATA_BYTES/4))*4;
  if(i%15==0)av=0;if(i%15==1)av=RING_DATA_BYTES+4;
  unsigned qhead=(i%2?0xfffff000u:STAGE_BYTES-2048u)+(random32()%1024)*4;
  unsigned used=(i%13==0)?STAGE_BYTES:(i%13==1)?STAGE_BYTES+4:random32()%4096*4;
  unsigned tail0=0xffff0000u+random32()%32768*4;
  unsigned pos0=random32()%(RING_DATA_BYTES/4)*4;
  memset(a,0,4096);memset(b,0,4096);
  a->head=b->head=qhead;a->tail=b->tail=qhead-used;
  unsigned touched=av>RING_DATA_BYTES?64:av+64;
  for(unsigned j=0;j<touched;j++)a->data[(qhead+j-32)&(STAGE_BYTES-1)]=b->data[(qhead+j-32)&(STAGE_BYTES-1)]=0xa7;
  ring->head_bytes=tail0+av;ring->tail_bytes=tail0;
  unsigned ta=tail0,tb=tail0,pa=pos0,pb=pos0;
  int ra=native(ring,a,&ta,&pa);unsigned ringtail=ring->tail_bytes;
  ring->tail_bytes=tail0;int rb=consume(ring,b,&tb,&pb);tests++;
  checked(ra==rb&&ta==tb&&pa==pb&&ringtail==ring->tail_bytes,"consume result differs");
  checked(!memcmp(a,b,4096),"consume shared header differs");
  for(unsigned j=0;j<touched;j++)checked(a->data[(qhead+j-32)&(STAGE_BYTES-1)]==b->data[(qhead+j-32)&(STAGE_BYTES-1)],"consume payload/guard differs");
 }
 munmap(a,STAGE_MAP_BYTES);munmap(b,STAGE_MAP_BYTES);free(ring);
 puts("PASS consume: 300 binary/C cases incl. wrap, full, guard and bad geometry");
}

static void test_radio(void){
 int (*native)(int,const struct radio_config*,u16*)=(void*)(image+0x11acc);
 unsigned count=0;
 for(unsigned k=0;k<24;k++){
  struct radio_config cfg={.lo=(u32[]){433000000u,900000000u,2112400000u,2167600000u}[k%4],
    .hi=k%3,.profile=k%11,.multiplier=(u8[]){10,20,40}[k%3],.lna=k%6,
    .band=k%2?8:1,.dvga=123,.uarfcn=k%2?2788:9750,.mode=(k/2)%2};
  config_now=&cfg;
  for(unsigned variant=0;variant<28;variant++){
   fail_step=variant>0&&variant<10?(int)variant:0;
   short_step=variant>=10&&variant<19?(int)variant-9:0;
   bad_step=variant>=19?(int)variant-18:0;
   wait_error=variant==26?26:0;gain_error=variant==27?28:0;
   struct event saved[64];u16 aa=0xbeef,ab=0xbeef;
   steps=0;oracle_side=1;int ra=native(17,&cfg,&aa);int na=steps;memcpy(saved,events,sizeof(saved));
   steps=0;oracle_side=0;int rb=arm_radio(17,&cfg,&ab);tests++;count++;
   if(ra!=rb||aa!=ab||na!=steps||memcmp(saved,events,na*sizeof(*events))){
    printf("radio k=%u variant=%u ret %d/%d anchor %u/%u steps %d/%d\n",k,variant,ra,rb,aa,ab,na,steps);
    checked(0,"radio state machine differs");
   }
  }
 }
 printf("PASS radio: %u binary/C cases incl. both modes and injected reply failures\n",count);
}

int main(int argc,char **argv){
 setbuf(stdout,NULL);
 checked(argc==2,"usage: oracle <RXFIX3 ELF>");
 int quiet=open("/dev/null",O_WRONLY);if(quiet>=0&&!getenv("ORACLE_VERBOSE")){dup2(quiet,2);close(quiet);}
 load_image(argv[1]);test_decode();test_feed();test_consume();test_radio();
 printf("PASS total %u differential cases; hardware accesses: 0\n",tests);
 return 0;
}

/* NDK static Bionic requires 32-byte TLS alignment on ARM. */
__thread int oracle_tls_alignment __attribute__((aligned(32))) = 1;
