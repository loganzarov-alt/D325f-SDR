/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Logan Zarov
 * Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
 */
.syntax unified
.arm
.text
.global lg_copy_burst
.type lg_copy_burst,%function
/* AAPCS wrapper for the existing RXFIX3 word-aligned copy operation.
 * r0=destination, r1=uncached SMEM source, r2=multiple-of-four byte length. */
lg_copy_burst:
    push {r4-r11,lr}
    cmp r2,#32
    blo 2f
1:
    ldmia r1!,{r4-r11}
    stmia r0!,{r4-r11}
    sub r2,r2,#32
    cmp r2,#32
    bhs 1b
2:
    cmp r2,#0
    beq 4f
3:
    ldr r4,[r1],#4
    str r4,[r0],#4
    subs r2,r2,#4
    bne 3b
4:
    pop {r4-r11,pc}
.size lg_copy_burst,.-lg_copy_burst
.section .note.GNU-stack,"",%progbits
