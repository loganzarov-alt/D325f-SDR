"""Run the actual RAM reclaimer against Android API fakes (selection/permissions)."""
import subprocess, tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[2]
stubs = {
 'android/os/Build.java': 'package android.os; public class Build {public static class VERSION {public static int SDK_INT=25;}}',
 'android/content/Intent.java': 'package android.content; public class Intent {public static final String ACTION_MAIN="main",CATEGORY_HOME="home";public Intent(String a){} public Intent addCategory(String c){return this;}}',
 'android/content/pm/ApplicationInfo.java': 'package android.content.pm; public class ApplicationInfo {public String packageName;public int uid;public ApplicationInfo(String p,int u){packageName=p;uid=u;}}',
 'android/content/pm/ResolveInfo.java': 'package android.content.pm; public class ResolveInfo {public static class ActivityInfo {public String packageName="home";}public ActivityInfo activityInfo=new ActivityInfo();}',
 'android/content/pm/PackageManager.java': '''package android.content.pm; import java.util.*;import android.content.Intent; public class PackageManager {
 public java.util.List<ApplicationInfo> getInstalledApplications(int n){return Arrays.asList(new ApplicationInfo("self",10042),new ApplicationInfo("shared",10042),new ApplicationInfo("android",1000),new ApplicationInfo("com.android.systemui",10043),new ApplicationInfo("keyboard",10044),new ApplicationInfo("home",10045),new ApplicationInfo("reader",10046),new ApplicationInfo("com.topjohnwu.magisk",10047),new ApplicationInfo("background",10048),new ApplicationInfo("com.google.android.gms",10049),new ApplicationInfo("denied",10050));}
 public ResolveInfo resolveActivity(Intent i,int f){return new ResolveInfo();}}''',
 'android/provider/Settings.java': '''package android.provider; public class Settings {public static class Secure {public static String DEFAULT_INPUT_METHOD="ime";public static String getString(Object r,String k){return k.equals("ime")?"keyboard/.IME":"reader/.Service";}}}''',
 'android/app/ActivityManager.java': '''package android.app; import java.util.*; public class ActivityManager {
 public static final List<String> killed=new ArrayList<>();int reads=0;public static class MemoryInfo {public long availMem;}
 public void getMemoryInfo(MemoryInfo i){i.availMem=(reads++==0?128L:192L)*1048576;}
 public void killBackgroundProcesses(String p){if(p.equals("denied"))throw new SecurityException();killed.add(p);}}''',
 'android/content/Context.java': '''package android.content; import android.app.ActivityManager;import android.content.pm.*;public class Context {
 public static final String ACTIVITY_SERVICE="activity";private ActivityManager am=new ActivityManager();
 public Object getSystemService(String s){return am;}public Object getContentResolver(){return null;}public PackageManager getPackageManager(){return new PackageManager();}public ApplicationInfo getApplicationInfo(){return new ApplicationInfo("self",10042);}}''',
 'TestReclaimer.java': '''import android.app.ActivityManager;import android.content.Context;import android.os.Build;import com.mantz_it.rfanalyzer.source.BackgroundReclaimer;public class TestReclaimer {
 public static void main(String[] a){String s=BackgroundReclaimer.reclaim(new Context());if(!ActivityManager.killed.equals(java.util.Arrays.asList("background","com.google.android.gms")))throw new AssertionError(ActivityManager.killed);if(!s.contains("128 → 192")||!s.contains("2 приложений"))throw new AssertionError(s);
 ActivityManager.killed.clear();Build.VERSION.SDK_INT=34;s=BackgroundReclaimer.reclaim(new Context());if(!ActivityManager.killed.isEmpty()||!s.contains("недоступна"))throw new AssertionError("SDK restriction");
 System.out.println("PASS: RAM reclamation excludes own/shared UID, core UI, IME, launcher, accessibility and root; denied calls and SDK restriction handled");}}'''
}
with tempfile.TemporaryDirectory() as directory:
 directory=Path(directory);sources=[]
 for name,content in stubs.items():
  p=directory/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(content);sources.append(str(p))
 sources.append(str(root/'app/src/main/java/com/mantz_it/rfanalyzer/source/BackgroundReclaimer.java'))
 subprocess.run(['javac','-encoding','UTF-8','-d',str(directory),*sources],check=True)
 subprocess.run(['java','-cp',str(directory),'TestReclaimer'],check=True,timeout=5)
