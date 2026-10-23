package android.content;
public class Context {
 public Context getApplicationContext(){return this;}
 public java.io.File getDir(String name,int mode){java.io.File f=new java.io.File(System.getenv("BTCW_TEST_DIR"),name);f.mkdirs();return f;}
 public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
}
