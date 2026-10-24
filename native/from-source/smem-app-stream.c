// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#define run unused_probe_run
#define _start unused_probe_start
#include "transport/pll-probe.c"
#undef run
#undef _start

/* RECONSTRUCTED C, 2026-10-23. NOT the missing original source text.
 * Based on preserved v11 C and the RXFIX3 ELF SHA-256
 * 02ba7e628d8809d3439cebeb3b1bd618d432dcd2564f73be6b8d52b16c5a90cc.
 * Current LFC2 ABI and control flow were transcribed from ARM disassembly.
 * No claim of byte-identical recompilation or untested hardware equivalence.
 */
#define SMEM_BASE       0x0d900000u
#define SMEM_MAP_BYTES  0x00100000u
#define RING_MAGIC      0x32534d51u
#define RING_DATA_BYTES 126976u
#define RING_CTRL       0x534d5235u
#define FREQ_CTRL       0x51463344u
#define INPUT_BYTES     24u
#define STAGE_BYTES     67108864u
#define STAGE_MAP_BYTES (STAGE_BYTES + 4096u)

struct ci_ring {
 u32 magic,version,format,capacity;
 volatile u32 active,head_bytes,tail_bytes,head_pos;
 volatile u32 produced_blocks,dropped_blocks,missed_slots,duplicate_irq;
 volatile u32 invalid_time,bad_geometry,late_copy,max_elapsed;
 u32 last_slot,scratch_ptr,generation,block_bytes;
 u32 block_samples,sample_rate,bandwidth_hz,reserved0;
 u32 reserved[40];u8 data[RING_DATA_BYTES];
};

struct stage_ring {
 volatile u32 head,tail,stop_requested,error,writer_done;
 volatile u32 config_seq,config_ack,stream_generation;
 volatile u32 total_lo,total_hi;
 volatile u32 max_used;
 volatile u32 diag_lock,keepalive_enabled,keepalive_anchor;
 volatile u8 config[INPUT_BYTES];
 volatile u32 reserved[1004];
 u8 data[STAGE_BYTES];
};

struct radio_config { u32 lo,hi; u8 profile,multiplier,lna,band; u16 dvga,uarfcn; u8 mode,flags; };

_Static_assert(__builtin_offsetof(struct ci_ring,data)==256, "SMEM data ABI");
_Static_assert(__builtin_offsetof(struct stage_ring,config)==56, "LFC2 stage ABI");
_Static_assert(__builtin_offsetof(struct stage_ring,data)==4096, "stage data ABI");
_Static_assert(sizeof(struct radio_config)==20, "config ABI");

/* Reuses the existing 32-byte RXFIX3 LDM/STM operation, with an AAPCS wrapper. */
extern void lg_copy_burst(void *dst,const volatile void *src,u32 bytes);
static long mmap2(void *a,u32 n,int prot,int flags,int fd,u32 pageoff) {
 register long r0 __asm__("r0")=(long)a,r1 __asm__("r1")=n,r2 __asm__("r2")=prot;
 register long r3 __asm__("r3")=flags,r4 __asm__("r4")=fd,r5 __asm__("r5")=pageoff,r7 __asm__("r7")=192;
 __asm__ volatile("svc 0":"+r"(r0):"r"(r1),"r"(r2),"r"(r3),"r"(r4),"r"(r5),"r"(r7):"memory");return r0;
}
static long fork2(void){return call3(2,0,0,0);}
static void pause_ms(u32 ms){long t[2];t[0]=ms/1000;t[1]=(ms%1000)*1000000;call3(162,(long)t,0,0);}
static void pause_half_ms(void){long t[2]={0,500000};call3(162,(long)t,0,0);}
static void barrier(void){__asm__ volatile("dmb sy":::"memory");}
static void diag_take(struct stage_ring *q){while(__atomic_exchange_n(&q->diag_lock,1u,__ATOMIC_ACQUIRE))pause_ms(1);}
static void diag_release(struct stage_ring *q){__atomic_store_n(&q->diag_lock,0u,__ATOMIC_RELEASE);}
static void set_cpu(u32 mask,int realtime){u32 priority=30;call3(241,0,4,(long)&mask);if(realtime)call3(156,0,1,(long)&priority);}
static void online_cpu1(void){int fd=sys_open("/sys/devices/system/cpu/cpu1/online",1,0);if(fd>=0){sys_write(fd,"1",1);call3(6,fd,0,0);}}
static void err(const char *s){u32 n=0;while(s[n])n++;call3(4,2,(long)s,n);}
static void err_u32(u32 v){char b[12];u32 n=0;do{b[n++]=(char)('0'+v%10);v/=10;}while(v);while(n)call3(4,2,(long)&b[--n],1);}
static void status(const char *name,u32 value){err(name);err_u32(value);err("\n");}

static u32 profile_word(u8 profile) {
 switch(profile) {
  case 1:return 0x8f7f1419u; case 2:return 0x8f7f3c19u;
  case 3:return 0x8f7f5c19u; case 4:return 0x90f804d1u;
  case 5:return 0x8f5c3419u; case 6:return 0x8f4e5419u;
  case 7:return 0x89f8741du; case 8:return 0x89d0241du;
  case 9:return 0x89c5441du; case 10:return 0x89bc641du;
  default:return 0;
 }
}
static u32 encoded_high(u32 hi,u8 profile,u8 multiplier) {
 u32 word=profile_word(profile);if(!word)return hi;
 if(multiplier==40)return hi==0?((word&~0x60000000u)|(1u<<29)):hi;
 if(multiplier==10)return hi<=1?((word&~0x60000000u)|((2u|hi)<<29)):hi;
 return hi==0?(word&~0x60000000u):hi;
}

static int ftm_word(int fd,unsigned cmd,u16 value) {
 u8 req[24];for(u32 i=0;i<24;i++)req[i]=0;
 put32(req,128);req[4]=75;req[5]=11;put16(req+6,1);put16(req+8,cmd);put16(req+10,value);
 packetlen=escaped=answerlen=0;sys_alarm(5);if(sys_write(fd,req,24)<0)return -1;
 for(u32 tries=0;tries<100;tries++){
  long got=sys_read(fd,readbuf,sizeof(readbuf));if(got<8||(u32)got>sizeof(readbuf))return -1;
  if(le32(readbuf)!=32)continue;u32 count=le32(readbuf+4),pos=8;if(count>1024)return -1;
  for(u32 k=0;k<count;k++){if(pos+4>(u32)got)return -1;u32 len=le32(readbuf+pos);pos+=4;if(len>(u32)got-pos)return -1;
   for(u32 j=0;j<len;j++){int rc=feed(readbuf[pos+j],cmd);if(rc)return rc;}pos+=len;}
 }return -1;
}

static int frequency_control(int fd,u32 op,u32 lo,u32 hi) {
 u8 req[28];for(u32 i=0;i<28;i++)req[i]=0;
 put32(req,128);req[4]=75;req[5]=11;put16(req+6,1);put16(req+8,902);
 put32(req+12,FREQ_CTRL);put32(req+16,op);put32(req+20,lo);put32(req+24,hi);
 packetlen=escaped=answerlen=0;sys_alarm(5);if(sys_write(fd,req,28)<0)return -1;
 for(u32 tries=0;tries<200;tries++){
  long got=sys_read(fd,readbuf,sizeof(readbuf));if(got<8)return -1;
  if(le32(readbuf)!=32)continue;u32 count=le32(readbuf+4),pos=8;if(count>1024)return -1;
  for(u32 k=0;k<count;k++){if(pos+4>(u32)got)return -1;u32 len=le32(readbuf+pos);pos+=4;if(len>(u32)got-pos)return -1;
   for(u32 j=0;j<len;j++){int rc=feed(readbuf[pos+j],902);if(rc)return rc;}pos+=len;}
 }return -1;
}

static u16 nearest_anchor(u32 lo,u32 hi,u8 band) {
 if(band==8)return 2788;if(hi||lo>=2167600000u)return 9888;
 if(lo<=2112400000u)return 9612;return (u16)((lo+100000u)/200000u-950u);
}

/* decode: original ELF 0x118c4..0x119ac. */
static int decode(const u8 *p,struct radio_config *c) {
 c->lo=le32(p);c->hi=le32(p+4);
 if(le32(p+8)!=0x3243464cu||le16(p+18)!=0x4c47u)return 1;
 c->profile=p[12];c->multiplier=p[13];c->lna=p[14];c->band=p[15];c->dvga=le16(p+16);
 c->uarfcn=le16(p+20);c->mode=p[22];c->flags=p[23];
 if((c->band!=1&&c->band!=8)||c->profile>10||c->lna>5)return 2;
 if(c->multiplier!=10&&c->multiplier!=20&&c->multiplier!=40)return 3;
 if(c->mode>1)return 4;
 if(!c->mode&&!c->lo&&!c->hi)return 5;
 return 0;
}

/* wait_pll: 0x129f4; apply_gain_controls: 0x12ab0. */
static int wait_pll(int fd,u32 *pll) {
 *pll=0;
 for(u32 n=0;n<20;n++){
  if(transaction(fd,262,0,0)!=1||answerlen<9)return 26;
  *pll=answer[7];if(*pll==2)return 0;pause_ms(100);
 }
 return *pll==2?0:29;
}
static int apply_gain_controls(int fd,const struct radio_config *c) {
 if(ftm_word(fd,0x003a,c->lna)!=1)return 27;
 if(ftm_word(fd,0x006f,c->dvga)!=1)return 28;
 return 0;
}

/* arm_radio: original ELF 0x11acc..0x12128. Both LFC2 modes preserved. */
static int arm_radio(int fd,const struct radio_config *c,u16 *anchor) {
 if(c->mode==1){
  u16 next=c->band==8?2788:9750;
  u32 expected=(u32)c->uarfcn*200000u+(c->band==8?385000000u:0u);
  if(transaction(fd,8,next,0)!=1)return 31;
  if(transaction(fd,901,256,0)!=1||answerlen<8||le16(answer+6))return 32;
  if(frequency_control(fd,3,c->band,c->uarfcn)!=1||answerlen<8||le16(answer+6))return 33;
  if(transaction(fd,8,next,0)!=1)return 34;
  u32 pll=0;int rc=wait_pll(fd,&pll);if(rc)return rc;
  if(frequency_control(fd,2,0,0)!=1||answerlen<48||le16(answer+6))return 35;
  if(le32(answer+32)!=expected||le32(answer+36)||le32(answer+40)!=expected||le32(answer+44))return 36;
  u32 word=le32(answer+24);
  if(frequency_control(fd,4,0,0)!=1||answerlen<40||le16(answer+6))return 37;
  if(le32(answer+16)!=c->band||le32(answer+20)!=c->uarfcn)return 38;
  rc=apply_gain_controls(fd,c);if(rc)return rc;
  if(transaction(fd,901,16128,0)!=1||answerlen<8||le16(answer+6))return 39;
  *anchor=next;
  err("READY ");err_u32(expected);err(" 0 PLL ");err_u32(pll);err(" BAND ");err_u32(c->band);
  err(" MODE UARFCN UARFCN ");err_u32(c->uarfcn);err(" PROFILE_WORD ");err_u32(word);err("\n");
  return 0;
 }
 u16 next=nearest_anchor(c->lo,c->hi,c->band),away=c->band==8?2798:(next==9750?9760:9750);
 if(transaction(fd,8,away,0)!=1)return 20;
 if(transaction(fd,901,256,0)!=1||answerlen<8||le16(answer+6))return 21;
 if(frequency_control(fd,1,c->lo,encoded_high(c->hi,c->profile,c->multiplier))!=1||answerlen!=48||le16(answer+6))return 22;
 if(transaction(fd,8,next,0)!=1)return 23;
 if(frequency_control(fd,2,0,0)!=1||answerlen!=48||le16(answer+6))return 24;
 if(le32(answer+16)!=0x485a4433u||!le32(answer+28)||le32(answer+40)!=c->lo)return 25;
 int rc=apply_gain_controls(fd,c);if(rc)return rc;
 u32 pll=0;rc=wait_pll(fd,&pll);
 if(!rc&&(transaction(fd,901,16128,0)!=1||answerlen<8||le16(answer+6)))return 30;
 *anchor=next;
 err("READY ");err_u32(c->lo);err(" ");err_u32(c->hi);err(" PLL ");err_u32(pll);err(" BAND ");err_u32(c->band);err(" MODE EXACT\n");
 return rc;
}

static int ring_command(int fd,u32 start) {
 return transaction(fd,902,RING_CTRL,start)==1&&answerlen>=60&&!answer[6]?0:1;
}

static int write_all_stdout(const u8 *p,u32 n) {
 u32 done=0;while(done<n){long got=call3(4,1,(long)(p+done),n-done);if(got>0){done+=(u32)got;continue;}if(got==-4)continue;if(got==-11){pause_ms(1);continue;}return 0;}return 1;
}

static void writer(struct stage_ring *q) {
 set_cpu(1u,0);
 u32 tail=0;for(;;){barrier();u32 head=q->head,available=head-tail;
  if(q->stop_requested)break;
  if(!available){pause_ms(1);continue;}
  u32 pos=tail&(STAGE_BYTES-1),n=available,edge=STAGE_BYTES-pos;if(n>edge)n=edge;if(n>1048576)n=1048576;
  if(!write_all_stdout(q->data+pos,n)){q->error=40;break;}
  u32 old=q->total_lo;q->total_lo=old+n;if(q->total_lo<old)q->total_hi++;
  tail+=n;barrier();q->tail=tail;
 }
 barrier();q->writer_done=1;sys_exit(q->error?1:0);
}

static void input_monitor(struct stage_ring *q) {
 set_cpu(1u,0);
 u8 command[INPUT_BYTES];u32 have=0;call3(55,0,4,2048); /* stdin O_NONBLOCK */
 while(!q->stop_requested){long n=sys_read(0,command+have,INPUT_BYTES-have);
  if(n==0){barrier();q->stop_requested=1;break;}
  if(n>0){have+=(u32)n;if(have==INPUT_BYTES){for(u32 i=0;i<INPUT_BYTES;i++)q->config[i]=command[i];barrier();q->config_seq++;have=0;}continue;}
  if(n!=-11&&n!=-4){q->error=41;q->stop_requested=1;break;}pause_ms(5);
 }sys_exit(q->error?1:0);
}

static int consume(struct ci_ring *s,struct stage_ring *q,u32 *tail,u32 *pos) {
 barrier();u32 head=s->head_bytes,available=head-*tail;if(available>RING_DATA_BYTES)return 1;
 while(available){barrier();u32 qhead=q->head,used=qhead-q->tail;if(used>STAGE_BYTES)return 2;
  u32 space=STAGE_BYTES-used;if(!space)return 3;u32 qpos=qhead&(STAGE_BYTES-1),n=available;
  if(used>q->max_used)q->max_used=used;
  u32 edge=RING_DATA_BYTES-*pos,qedge=STAGE_BYTES-qpos;if(n>edge)n=edge;if(n>qedge)n=qedge;if(n>space)n=space;if(n>4096u)n=4096u;
  if(n)lg_copy_burst(q->data+qpos,s->data+*pos,n);
  barrier();q->head=qhead+n;*pos+=n;if(*pos==RING_DATA_BYTES)*pos=0;*tail+=n;barrier();s->tail_bytes=*tail;available-=n;
 }return 0;
}

static int app_stream(void) {
 u8 initial[INPUT_BYTES];u32 have=0;while(have<INPUT_BYTES){long n=sys_read(0,initial+have,INPUT_BYTES-have);if(n<=0)return 2;have+=(u32)n;}
 struct radio_config config;if(decode(initial,&config))return 3;
 long lock=sys_open("/data/local/tmp/lg-iq-sampler.lock",66,0600);if(lock<0||call3(143,lock,6,0)<0)return 4;
 int memfd=sys_open("/dev/mem",2,0);if(memfd<0)return 5;
 long mapping=mmap2(0,SMEM_MAP_BYTES,3,1,memfd,SMEM_BASE>>12);if((u32)mapping>=0xfffff000u)return 6;
 volatile u32 *toc=(volatile u32*)(mapping+0xd0u+471u*16u);if(toc[0]!=1||toc[2]<131072||toc[3])return 7;
 struct ci_ring *s=(struct ci_ring*)(mapping+toc[1]);
 long stage_map=mmap2(0,STAGE_MAP_BYTES,3,0x21,-1,0);if((u32)stage_map>=0xfffff000u)return 8;
 struct stage_ring *q=(struct stage_ring*)stage_map;
 /* RXFIX3: write one word per page before mlock, then return mlock errno. */
 for(u32 i=0;i<STAGE_MAP_BYTES;i+=4096u)*(volatile u32*)((u8*)q+i)=0;
 long prepared=call3(150,stage_map,STAGE_MAP_BYTES,0);if(prepared)return (int)prepared;
 online_cpu1();
 int fd=sys_open("/dev/diag",2,0);if(fd<0||sys_ioctl(fd,7,(void*)6)<0)return 9;
 if(transaction(fd,7,9,0)!=1)return 10;pause_ms(1000);
 u16 anchor=0;int rc=arm_radio(fd,&config,&anchor);if(rc)return rc;
 long child=fork2();if(child<0)return 11;if(child==0)writer(q);
 long monitor=fork2();if(monitor<0){q->stop_requested=1;return 12;}if(monitor==0)input_monitor(q);
 set_cpu(2u,1);
 if(ring_command(fd,1)){q->stop_requested=1;return 13;}sys_alarm(0);
 barrier();if(s->magic!=RING_MAGIC||s->format!=8||s->capacity!=RING_DATA_BYTES){q->stop_requested=1;return 14;}
 q->keepalive_anchor=anchor;q->keepalive_enabled=0;
 u32 tail=s->tail_bytes,pos=0,seen_config=0,idle=0;q->stream_generation=1;err("STREAM CI8 7680000 5000000 MLOCK_RXFIX_3\n");
 while(!q->stop_requested&&!q->error){
  int cr=consume(s,q,&tail,&pos);if(cr){q->error=50+cr;break;}
  if(s->active!=1u||s->bad_geometry){q->error=54;break;}
  barrier();if(q->config_seq!=seen_config){
   seen_config=q->config_seq;struct radio_config next;u8 copy[INPUT_BYTES];for(u32 i=0;i<INPUT_BYTES;i++)copy[i]=q->config[i];
   if(decode(copy,&next)){q->error=55;break;}
   q->keepalive_enabled=0;diag_take(q);int re=0;
   if(ring_command(fd,0))re=56;
   if(!re)for(u32 n=0;n<1000;n++){consume(s,q,&tail,&pos);barrier();if(s->head_bytes==tail)break;}
   if(!re){rc=arm_radio(fd,&next,&anchor);if(rc)re=rc;}
   if(!re&&ring_command(fd,1))re=57;
   sys_alarm(0);diag_release(q);
   if(re){q->error=re;break;}config=next;tail=s->tail_bytes;pos=0;q->keepalive_anchor=anchor;q->keepalive_enabled=0;q->stream_generation++;q->config_ack=seen_config;
  }
  if(s->head_bytes==tail){if(++idle>8){pause_half_ms();idle=0;}}else idle=0;
 }
 q->keepalive_enabled=0;diag_take(q);ring_command(fd,0);sys_alarm(0);for(u32 n=0;n<1000;n++){consume(s,q,&tail,&pos);barrier();if(s->head_bytes==tail)break;}
 if(frequency_control(fd,2,0,0)==1&&answerlen==48&&!le16(answer+6)){err("FINAL_FREQ ");err_u32(le32(answer+40));err(" ");err_u32(le32(answer+44));err("\n");}frequency_control(fd,0,0,0);sys_alarm(0);diag_release(q);
 barrier();q->stop_requested=1;for(u32 n=0;n<10000&&!q->writer_done;n++)pause_ms(1);
 status("BLOCKS ",s->produced_blocks);status("DROPPED ",s->dropped_blocks);status("MISSED ",s->missed_slots);status("STAGE_ERROR ",q->error);
 status("STAGE_MAX_USED ",q->max_used);status("TOTAL_LO ",q->total_lo);
 return q->error?60:0;
}

#ifndef LG_STREAM_TEST
__attribute__((noreturn)) void _start(void){int rc=app_stream();sys_alarm(0);sys_exit(rc);}

#endif
