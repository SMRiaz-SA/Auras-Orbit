package audit;
import android.content.Intent;
import com.auras.orbit.intent.RequestValidation;
import com.auras.orbit.providers.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

public class OrbitJvmProbes {
 static int assertions=0, failures=0;
 static void check(boolean condition, String message) { assertions++; if (!condition) {failures++;if(failures<=12)System.out.println("FAILED "+message);} }
 static String fuzzString(Random r){
  String chars="abcXYZ01:/%?#@&=_+- !~\\\n\t.,()[]{}";
  int n=r.nextInt(360);
  StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(chars.charAt(r.nextInt(chars.length())));
  return s.toString();
 }
 static void testRequestParser() {
  Random r=new Random(20261108L);
  String[] actions={Intent.ACTION_VIEW,Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE,"not-a-real-action"};
  String[] prefixes={"https://valid.example/","http://127.0.0.1/path","cloudstreamsearch://", "cloudstreamplayer://", "cloudstreamrepo://", "javascript:","content://some.provider/","csshare:","cloudstreamapp://"};
  for(int n=0;n<12000;n++){
   String value=prefixes[r.nextInt(prefixes.length)]+fuzzString(r);
   if(n%5==0)value=fuzzString(r);
   int size=1+r.nextInt(10);
   ArrayList<String> vals=new ArrayList<>();for(int j=0;j<size;j++)vals.add(j==0?value:fuzzString(r));
   RequestValidation output=null;
   try{output=RequestValidation.parse(actions[r.nextInt(actions.length)],"text/plain", vals);}
   catch(Throwable t){check(false,"parser exception input="+n+" "+t.getClass().getSimpleName());continue;}
   check(output.getItems().size()<=9,"item bound n="+n);
   check(!output.getItems().isEmpty(),"nonempty outcome n="+n);
   for(RequestValidation.Item item:output.getItems()){
      String display=item.getSafeDisplayText();
      check(display.length()<600,"display bound n="+n);
      check(item.disposition!=RequestValidation.Disposition.REQUIRES_CONFIRMATION || item.getSafeDisplayText()!=null,"display nonnull n="+n);
      if(item.disposition==RequestValidation.Disposition.REJECTED){
       check(item.getHttpUrlForConfirmedAction()==null,"rejected HTTP access n="+n);
       check(item.getRepositoryInputForConfirmedAction()==null,"rejected repo access n="+n);
       check(item.getPlayerUrlForConfirmedAction()==null,"rejected player access n="+n);
      }
   }
  }
  check(RequestValidation.parse(Intent.ACTION_VIEW,"", List.of("https://user:secret@example.org/a")).getItems().get(0).isRejected(),"credentials refused");
  check(RequestValidation.parse(Intent.ACTION_VIEW,"", List.of("javascript:alert(1)")).getItems().get(0).isRejected(),"javascript refused");
  check(RequestValidation.parse(Intent.ACTION_VIEW,"", List.of("http://127.0.0.1/private")).requiresConfirmation(),"loopback link requires user confirmation");
  check(RequestValidation.parse(Intent.ACTION_VIEW,"", List.of("cloudstreamsearch://hello%20world")).requiresConfirmation(),"encoded search parsed");
 }
 static final class Provider implements MediaProvider{
  final ProviderDescriptor desc;final CountDownLatch latch;
  Provider(String id, CountDownLatch latch){this.desc=new ProviderDescriptor(id,id,false,true,true,false,false);this.latch=latch;}
  public ProviderDescriptor descriptor(){return desc;}
  public List<MediaItem> search(SearchRequest request)throws Exception{
   if(latch!=null)while(latch.getCount()>0)try{latch.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
   return Collections.emptyList();
  }
 }
 static void testProviderQueue() throws Exception{
  int batchCount=1200;
  ProviderOperationCoordinator coord=new ProviderOperationCoordinator(1);
  CountDownLatch latch=new CountDownLatch(1);
  Provider blocker=new Provider("provider-blocker",latch);
  List<ProviderBatch<?>> batches=new ArrayList<>();
  batches.add(coord.search(List.of(blocker),new SearchRequest("probe",SearchType.ALL,1),8000,ignored -> {}));
  Thread.sleep(100);
  for(int n=0;n<batchCount;n++){
   Provider p=new Provider("probe-"+n,null);
   batches.add(coord.search(List.of(p),new SearchRequest("probe",SearchType.ALL,1),20,ignored -> {}));
  }
  Thread.sleep(200);
  Field f=ProviderOperationCoordinator.class.getDeclaredField("workers");f.setAccessible(true);
  ThreadPoolExecutor pool=(ThreadPoolExecutor)f.get(coord);
  int queue=pool.getQueue().size();
  int timedOut=0;
  for(ProviderBatch<?> b:batches)if(b.isComplete())timedOut++;
  System.out.println("QUEUE_PROBE batch_count="+batchCount+" threads="+pool.getPoolSize()+" queued_tasks_after_timeout="+queue+" complete_batches="+timedOut);
  check(queue==0,"cancelled queued tasks must be removed, actual queue="+queue);
  check(timedOut>batchCount/2,"batch status timeout progress");
  for(ProviderBatch<?> b:batches)b.close();
  latch.countDown();coord.close();
 }
 static void testProviderResults() throws Exception{
  ProviderOperationCoordinator coord=new ProviderOperationCoordinator(2);
  for(int n=0;n<350;n++){
   MediaProvider p=new Provider("quick-"+n,null);
   ProviderBatch<List<AttributedSearchHit>> batch=coord.search(List.of(p),new SearchRequest("query",SearchType.ALL,1),1000,ignored -> {});
   for(int i=0;i<100 && !batch.isComplete();i++) Thread.sleep(1);
   check(batch.isComplete(),"search completes n="+n);
   check(batch.snapshot().get(0).status==ProviderOutcome.Status.SUCCESS,"search correct result n="+n);
   check(batch.snapshot().get(0).value!=null && batch.snapshot().get(0).value.isEmpty(),"zero results n="+n);
   batch.close();
  }
  coord.close();
 }
 public static void main(String[] args)throws Exception {
  testRequestParser();testProviderQueue();testProviderResults();
  System.out.println("ACTUAL_SOURCE_JVM_PROBES assertions="+assertions+" failures="+failures);
  if(failures>0)System.exit(1);
 }
}
