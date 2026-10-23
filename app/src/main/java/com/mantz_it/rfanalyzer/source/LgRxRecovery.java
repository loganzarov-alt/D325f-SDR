package com.mantz_it.rfanalyzer.source;

import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.WeakHashMap;

/* loaded from: classes4.dex */
public final class LgRxRecovery {
    private static final String FACTORY = "response=0204000000000001010002";
    private static final String ONLINE = "response=0204000000000001010000";
    private static final String TAG = "LgRxRecovery";
    private static int failures;
    private static final WeakHashMap<Object, Attempt> attempts = new WeakHashMap<>();
    private static RootRunner runner = new NativeRootRunner();

    interface RootRunner {
        String run(File file) throws InterruptedException, IOException;
    }

    private static final class Attempt {
        boolean failed;
        boolean ready;

        private Attempt() {
        }
    }

    private static final class NativeRootRunner implements RootRunner {
        private NativeRootRunner() {
        }

        @Override // com.mantz_it.rfanalyzer.source.LgRxRecovery.RootRunner
        public String run(File file) throws InterruptedException, IOException {
            if (file == null || !file.isFile()) {
                throw new IOException("RX recovery helper is missing");
            }
            Process processStart = new ProcessBuilder("su", "-c", "exec timeout -k 1 -s TERM 6 " + ("'" + file.getAbsolutePath().replace("'", "'\\''") + "'")).redirectErrorStream(true).start();
            try {
                processStart.getOutputStream().close();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                try (InputStream inputStream = processStart.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = inputStream.read(buffer)) != -1) {
                        if (byteArrayOutputStream.size() + count > 16384) {
                            throw new IOException("RX recovery helper output exceeded limit");
                        }
                        byteArrayOutputStream.write(buffer, 0, count);
                    }
                }
                int rc = processStart.waitFor();
                String output = byteArrayOutputStream.toString("UTF-8");
                if (rc != 0) throw new IOException("RX recovery helper rc=" + rc + ": " + output);
                return output;
            } finally {
                processStart.destroy();
            }
        }
    }

    private static void requireMode(File file, String str, String str2) throws InterruptedException, IOException {
        String strRun = "";
        IOException e = null;
        for (int i = 1; i <= 3; i++) {
            try {
                strRun = runner.run(file);
                if (strRun.contains(str)) {
                    Log.i(TAG, str2 + " confirmed on attempt " + i);
                    return;
                }
                e = null;
            } catch (IOException e2) {
                e = e2;
            }
            if (i < 3) {
                Thread.sleep(400L);
            }
        }
        throw new IOException(str2 + " was not confirmed; RX retry blocked: " + strRun, e);
    }

    public static synchronized void beforeStart(Object obj, File file, File file2) throws InterruptedException, IOException {
        if (failures >= 2) {
            Log.w(TAG, "ONLINE recovery after two failed RX starts");
            requireMode(file, ONLINE, "ONLINE");
            requireMode(file2, FACTORY, "FACTORY");
            failures = 0;
            Log.i(TAG, "ONLINE -> FACTORY recovery complete; retrying RX");
        }
        attempts.put(obj, new Attempt());
    }

    public static synchronized void failed(Object obj, String str) {
        Attempt attempt = attempts.get(obj);
        if (attempt != null && !attempt.failed && !attempt.ready) {
            attempt.failed = true;
            failures++;
            Log.w(TAG, "RX start failure " + failures + "/2: " + str);
        }
    }

    public static synchronized void received(Object obj) {
        Attempt attempt = attempts.get(obj);
        if (attempt != null && !attempt.failed && !attempt.ready) {
            attempt.ready = true;
            failures = 0;
            Log.i(TAG, "First IQ packet received; failed-start counter cleared");
        }
    }

    static synchronized void testRunner(RootRunner rootRunner) {
        attempts.clear();
        failures = 0;
        runner = rootRunner;
    }

    private LgRxRecovery() {
    }
}
