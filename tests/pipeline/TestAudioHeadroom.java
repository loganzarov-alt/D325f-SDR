import com.mantz_it.rfanalyzer.analyzer.AudioHeadroom;
public class TestAudioHeadroom {
    static void check(double expected) {
        if (AudioHeadroom.fftCpuBudget()!=expected) throw new AssertionError("FFT budget");
    }
    public static void main(String[] args) {
        AudioHeadroom h=new AudioHeadroom(32000);h.begin();check(.08);
        h.wrote(4800);h.update(0);check(.35);
        h.update(2000);check(.35); // 87 ms: retain the previous state
        h.update(3200);check(.08); // 50 ms: yield CPU to audio
        h.wrote(1600);h.update(3200);check(.08); // 100 ms: hysteresis
        h.wrote(1600);h.update(3200);check(.35);
        AudioHeadroom newer=new AudioHeadroom(32000);newer.begin();h.end();check(.08);
        newer.wrote(4800);newer.update(0);check(.35);h.end();check(.35);
        newer.end();check(.08);
        AudioHeadroom wrap=new AudioHeadroom(32000);wrap.begin();
        wrap.wrote(Integer.MAX_VALUE);wrap.wrote(Integer.MAX_VALUE);wrap.wrote(4802);
        wrap.update(-1);check(.35);wrap.update(1);check(.35);
        wrap.update(3200);check(.08);
        wrap.end();
        System.out.println("PASS: audio headroom controls FFT budget, hysteresis, replacement and unsigned playback counter wrap");
    }
}
