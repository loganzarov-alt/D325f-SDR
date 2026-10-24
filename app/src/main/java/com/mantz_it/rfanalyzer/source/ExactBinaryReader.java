// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Read stdout as bytes. Never merge su/helper diagnostics into IQ. */
public final class ExactBinaryReader {
    private ExactBinaryReader() {}

    public static byte[] read(InputStream input, int expected) throws IOException {
        byte[] bytes = new byte[expected];
        int offset = 0;
        while (offset < expected) {
            int count = input.read(bytes, offset, expected - offset);
            if (count < 0) throw new IOException("IQ transfer ended at " + offset + "/" + expected + " bytes");
            if (count == 0) continue;
            offset += count;
        }
        if (input.read() != -1) throw new IOException("IQ transfer exceeds " + expected + " bytes");
        return bytes;
    }

    public static byte[] run(Process process, int expected, long timeoutMs) throws IOException, InterruptedException {
        final byte[][] result = new byte[1][];
        final IOException[] failure = new IOException[1];
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread stdout = new Thread(() -> {
            try { result[0] = read(process.getInputStream(), expected); }
            catch (IOException e) { failure[0] = e; }
        }, "GSM-IQ-transfer");
        Thread stderr = new Thread(() -> {
            byte[] block = new byte[512];
            try {
                int count;
                while ((count = process.getErrorStream().read(block)) >= 0) {
                    synchronized (errors) {
                        if (errors.size() < 4096) errors.write(block, 0, Math.min(count, 4096 - errors.size()));
                    }
                }
            } catch (IOException ignored) {}
        }, "GSM-IQ-errors");
        stdout.setDaemon(true); stderr.setDaemon(true);
        stdout.start(); stderr.start();
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        Integer exit = null;
        try {
            while (exit == null && System.nanoTime() < deadline) {
                try { exit = process.exitValue(); }
                catch (IllegalThreadStateException running) { Thread.sleep(20); }
            }
            if (exit == null) throw new IOException("IQ transfer timed out");
            stdout.join(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L));
            stderr.join(200);
            if (stdout.isAlive()) throw new IOException("IQ stdout did not close");
            String detail;
            synchronized (errors) { detail = errors.toString("UTF-8"); }
            if (exit != 0) throw new IOException("IQ read failed (" + exit + "): " + detail);
            if (failure[0] != null) throw new IOException(failure[0].getMessage() + "; " + detail, failure[0]);
            if (result[0] == null) throw new IOException("IQ transfer returned no result");
            return result[0];
        } finally {
            process.destroy();
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            try { process.getErrorStream().close(); } catch (IOException ignored) {}
            try { process.getOutputStream().close(); } catch (IOException ignored) {}
        }
    }
}
