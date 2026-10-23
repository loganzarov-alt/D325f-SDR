import com.mantz_it.rfanalyzer.source.ExactBinaryReader;
import java.io.*;
import java.util.Arrays;

public class TestExactBinaryReader {
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        byte[] expected = new byte[32768];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte)i;
        InputStream fragments = new ByteArrayInputStream(expected) {
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(17, len));
            }
        };
        check(Arrays.equals(expected, ExactBinaryReader.read(fragments, 32768)));
        for (int size : new int[]{0, 32767, 32769}) {
            try {
                ExactBinaryReader.read(new ByteArrayInputStream(new byte[size]), 32768);
                throw new AssertionError("Accepted wrong length " + size);
            } catch (IOException correct) {}
        }
        Process valid = new ProcessBuilder("python3", "-c",
            "import sys; sys.stderr.write('diagnostic only\\n'); sys.stdout.buffer.write(bytes(range(256))*128)").start();
        check(Arrays.equals(expected, ExactBinaryReader.run(valid, 32768, 3000)));
        Process failed = new ProcessBuilder("python3", "-c",
            "import sys; sys.stdout.buffer.write(bytes(range(256))*128); sys.exit(2)").start();
        try { ExactBinaryReader.run(failed, 32768, 3000); throw new AssertionError(); }
        catch (IOException correct) {}
        Process slow = new ProcessBuilder("python3", "-c", "import time; time.sleep(10)").start();
        long start = System.nanoTime();
        try { ExactBinaryReader.run(slow, 32768, 150); throw new AssertionError(); }
        catch (IOException correct) {}
        check(System.nanoTime() - start < 3_000_000_000L);
        System.out.println("GSM binary transfer: fragmented, exact, empty, short, oversized, stderr, exit status and timeout PASS");
    }
}
