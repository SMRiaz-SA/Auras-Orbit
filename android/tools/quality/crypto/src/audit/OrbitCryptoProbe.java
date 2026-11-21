package audit;
import com.auras.orbit.backup.OrbitLibraryArchiveCodec;
import org.json.JSONObject;
import java.util.*;import java.io.*;import java.lang.reflect.*;import javax.crypto.*;import javax.crypto.spec.*;
public final class OrbitCryptoProbe{
 static int checks=0, failures=0;
 static void check(boolean ok,String reason){checks++;if(!ok){failures++;System.out.println("FAILED: "+reason);}}
 static final char[] PASS="correct horse battery staple".toCharArray();
 static final char[] WRONG="incorrect password!".toCharArray();
 static byte[] write(String value)throws Exception {ByteArrayOutputStream b=new ByteArrayOutputStream();OrbitLibraryArchiveCodec.write(new JSONObject(value),b,PASS);return b.toByteArray();}
 static boolean rejects(byte[] bytes,char[] password){try{OrbitLibraryArchiveCodec.read(new ByteArrayInputStream(bytes),password);return false;}catch(OrbitLibraryArchiveCodec.ArchiveException expected){return true;}catch(Exception error){return false;}}
 public static void main(String[] args)throws Exception{
  long start=System.nanoTime();
  Method derive=OrbitLibraryArchiveCodec.class.getDeclaredMethod("deriveKey",byte[].class,byte[].class,int.class);derive.setAccessible(true);
  byte[] password="example-password".getBytes("UTF-8");byte[] salt="salt-123456789012".getBytes("UTF-8");
  byte[] ours=(byte[])derive.invoke(null,password,salt,250000);
  byte[] reference=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec("example-password".toCharArray(),salt,250000,256)).getEncoded();
  check(Arrays.equals(ours,reference),"PBKDF2 reference mismatch");
  for(int n=0;n<8;n++){
   String json="{\"n\":"+n+",\"description\":\"Orbit library audit payload\"}";
   byte[] blob=write(json);
   check(json.equals(OrbitLibraryArchiveCodec.read(new ByteArrayInputStream(blob),PASS).toString()),"roundtrip "+n);
   if(n<4){check(rejects(blob,WRONG),"wrong password "+n);byte[] mutated=blob.clone();mutated[mutated.length/2]^=0x20;check(rejects(mutated,PASS),"authentication integrity "+n);}
   if(n==0){byte[] badHeader=blob.clone();badHeader[0]^=0x01;check(rejects(badHeader,PASS),"header magic tamper");}
  }
  // A broken provider that returns zero for positive-length reads must fail quickly.
  // Without the guard the original archive reader loops indefinitely.
  InputStream nonProgressing = new InputStream() {
   @Override public int read(){return 0;}
   @Override public int read(byte[] buffer,int offset,int length){return 0;}
  };
  boolean boundedFailure=false;
  try { OrbitLibraryArchiveCodec.read(nonProgressing,PASS); }
  catch(IOException expected){boundedFailure=true;}
  check(boundedFailure,"non-progressing archive stream must terminate");
  long elapsed=(System.nanoTime()-start)/1000000;
  System.out.println("REAL_ARCHIVE_CRYPTO_PROBE assertions="+checks+" failures="+failures+" elapsed_ms="+elapsed);
  if(failures>0)System.exit(1);
 }
}
