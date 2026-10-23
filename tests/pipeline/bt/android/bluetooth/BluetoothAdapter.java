package android.bluetooth;
public class BluetoothAdapter {
 public static final int STATE_OFF=10;
 private static final BluetoothAdapter adapter=new BluetoothAdapter();
 private long offAt=Long.MAX_VALUE;
 public static BluetoothAdapter getDefaultAdapter(){return adapter;}
 public int getState(){return System.nanoTime()/1000000>=offAt?STATE_OFF:12;}
 public boolean disable(){offAt=System.nanoTime()/1000000+600;return true;}
}
