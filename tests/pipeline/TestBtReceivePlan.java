import com.mantz_it.rfanalyzer.source.BtReceivePlan;
public class TestBtReceivePlan {
 public static void main(String[] args) {
  for(int ch=0;ch<=78;ch++)for(long rf:new long[]{1,20000000,100000000,433000000,799999999,800000000,2400000000L}) {
   long p=BtReceivePlan.receiverFrequency(rf,ch);
   long expected=rf<800000000 ? (2402L+ch)*1000000-rf : rf;
   if(p!=expected)throw new AssertionError("Mixing mapping");
   if(BtReceivePlan.force1000x20(rf,p)!=(rf<800000000&&p>=1548200000&&p<=2457500000L))throw new AssertionError("Profile");
  }
  if(!BtReceivePlan.force1000x20(1,2457500000L)||BtReceivePlan.force1000x20(1,2457500001L)||BtReceivePlan.force1000x20(800000000,2402000000L))throw new AssertionError("Boundary");
  com.mantz_it.rfanalyzer.source.BtRxLimit.activeHz=500000000;
  if(BtReceivePlan.receiverFrequency(600000000,0)!=600000000)throw new AssertionError("Lower configured threshold");
  com.mantz_it.rfanalyzer.source.BtRxLimit.activeHz=900000000;
  if(BtReceivePlan.receiverFrequency(850000000,0)!=1552000000)throw new AssertionError("Raised configured threshold");
  com.mantz_it.rfanalyzer.source.BtRxLimit.activeHz=800000000;
  System.out.println("PASS: BT channels, difference frequency, 1000x20 boundaries, direct RX");
 }
}
