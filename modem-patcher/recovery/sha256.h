#ifndef D325_SHA256_H
#define D325_SHA256_H
#include <stdint.h>
#include <stddef.h>
typedef struct {uint32_t h[8];uint64_t bytes;uint8_t block[64];size_t used;} d325_sha256;
void sha_init(d325_sha256 *s);
void sha_update(d325_sha256 *s,const void *data,size_t bytes);
void sha_final(d325_sha256 *s,uint8_t out[32]);
void sha_bytes(const void *data,size_t bytes,uint8_t out[32]);
#endif
