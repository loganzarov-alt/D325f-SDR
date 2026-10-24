// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
typedef unsigned int u32;
typedef unsigned char u8;
typedef void *qmi_client_type;
typedef void *qmi_service_object_type;

extern qmi_service_object_type dms_get_service_object_internal_v01(int, int, int);
extern int qmi_client_init_instance(qmi_service_object_type, u32, void *, void *,
                                    void *, u32, qmi_client_type *);
extern int qmi_client_send_raw_msg_sync(qmi_client_type, u32, void *, u32,
                                        void *, u32, u32 *, u32);
extern int qmi_client_release(qmi_client_type);

static long sys_write(int fd, const void *buf, u32 len) {
    register long r0 __asm__("r0") = fd;
    register const void *r1 __asm__("r1") = buf;
    register u32 r2 __asm__("r2") = len;
    register long r7 __asm__("r7") = 4;
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

static int run(void) {
    u8 os_params[256];
    u8 response[128];
    u32 response_len = 0;
    qmi_client_type client = 0;
    qmi_service_object_type service;
    int rc;
    u32 i;

    for (i = 0; i < sizeof(os_params); i++) os_params[i] = 0;
    for (i = 0; i < sizeof(response); i++) response[i] = 0;

    service = dms_get_service_object_internal_v01(1, 40, 6);
    out("service="); out_hex((u32)service);
    if (!service) return 10;

    rc = qmi_client_init_instance(service, 0xffff, 0, 0, os_params, 5000, &client);
    out("init_rc="); out_hex((u32)rc);
    out("client="); out_hex((u32)client);
    if (rc || !client) return 11;

#ifdef D325_SET_MODE
    {
        u8 set_request[4] = {0x01, 0x01, 0x00, D325_SET_MODE};
        u8 set_response[32];
        u32 set_response_len = 0;
        for (i = 0; i < sizeof(set_response); i++) set_response[i] = 0;
        rc = qmi_client_send_raw_msg_sync(client, 0x2e,
                                          set_request, sizeof(set_request),
                                          set_response, sizeof(set_response),
                                          &set_response_len, 5000);
        out("set_mode_rc="); out_hex((u32)rc);
        out("set_response_len="); out_hex(set_response_len);
        out("set_response=");
        for (i = 0; i < set_response_len; i++) {
            static const char digits[] = "0123456789abcdef";
            char pair[2];
            pair[0] = digits[set_response[i] >> 4];
            pair[1] = digits[set_response[i] & 15];
            sys_write(1, pair, 2);
        }
        out("\n");
        if (rc) return 13;
    }
#endif

    rc = qmi_client_send_raw_msg_sync(client, 0x2d, 0, 0,
                                      response, sizeof(response), &response_len, 5000);
    out("get_mode_rc="); out_hex((u32)rc);
    out("response_len="); out_hex(response_len);
    out("response=");
    for (i = 0; i < response_len; i++) {
        static const char digits[] = "0123456789abcdef";
        char pair[2];
        pair[0] = digits[response[i] >> 4];
        pair[1] = digits[response[i] & 15];
        sys_write(1, pair, 2);
    }
    out("\n");
    /* Process exit closes the client. qmi_client_release() blocks forever in
       this old vendor transport when called from this tiny standalone tool. */
    return rc ? 12 : 0;
}

__attribute__((noreturn)) void _start(void) {
    sys_exit(run());
}
