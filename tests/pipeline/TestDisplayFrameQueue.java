import com.mantz_it.rfanalyzer.dsp.DisplayFrameQueue;
public class TestDisplayFrameQueue {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception{
        DisplayFrameQueue q=new DisplayFrameQueue();q.configure(2,1);
        float[] src={0,0};float[][] dst=new float[6][2];long[] times=new long[6];
        for(int i=0;i<10;i++){src[0]=src[1]=i;q.offer(src,i,1);}
        src[0]=-1;
        check(q.droppedFrames()==4);check(q.drain(dst,times,1)==6);
        for(int i=0;i<6;i++){check(dst[i][0]==i+4);check(times[i]==i+4);}
        check(q.drain(dst,times,1)==0);
        q.offer(src,11,1);q.configure(2,2);check(q.drain(dst,times,2)==0);
        q.offer(src,12,1);check(q.drain(dst,times,2)==0);
        final java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        Thread producer=new Thread(()->{try{float[] v=new float[2];for(int i=0;i<100000;i++){v[0]=v[1]=i;q.offer(v,i,2);}}catch(Throwable e){error.set(e);}});
        producer.start();long previous=-1;
        while(producer.isAlive()){
            int n=q.drain(dst,times,2);
            for(int i=0;i<n;i++){check(times[i]>previous);check(dst[i][0]==dst[i][1]);check(dst[i][0]==times[i]);previous=times[i];}
        }
        producer.join();check(error.get()==null);
        System.out.println("PASS: bounded frame batches, overload eviction, ownership copies, viewport epoch reset, concurrent producer/renderer");
    }
}
