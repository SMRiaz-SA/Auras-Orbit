package audit;
import com.auras.orbit.player.PlaybackRequest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Compiles the production PlaybackRequest with contract-only Android/JSON stubs. */
public final class PlaybackCollectionAdmissionRegression {
 private static long assertions;
 private static void check(boolean valid, String message) { assertions++; if (!valid) throw new AssertionError(message); }
 public static void main(String[] args) {
  PlaybackRequest.MediaMetadata media = new PlaybackRequest.MediaMetadata("id", "prov", "title", "movie", "", "", "");
  PlaybackRequest.Source source = new PlaybackRequest.Source("s", "HD", "https://example.org/test.mp4", "video/mp4", Collections.emptyMap(), Collections.emptyList());
  AtomicInteger scan = new AtomicInteger();
  List<PlaybackRequest.SkipSegment> hostile = new AbstractList<PlaybackRequest.SkipSegment>() {
   @Override public int size() { return 1; }
   @Override public PlaybackRequest.SkipSegment get(int i) {return new PlaybackRequest.SkipSegment("seg-"+i,"intro","intro",i,i+1);}
   @Override public Iterator<PlaybackRequest.SkipSegment> iterator() {
    return new Iterator<PlaybackRequest.SkipSegment>() {
      @Override public boolean hasNext(){return true;}
      @Override public PlaybackRequest.SkipSegment next(){if(scan.incrementAndGet()>200)throw new AssertionError("Unbounded playback iterator"); return new PlaybackRequest.SkipSegment("seg-"+scan.get(),"intro","intro",0,1);}
    };
   }
  };
  PlaybackRequest request = new PlaybackRequest("p", media, null, Collections.singletonList(source), "s", hostile, Collections.emptyList());
  check(request.skipSegments.size()==1, "Playback skipped untrusted size cap");
  check(scan.get()<=1, "Playback iterated beyond recorded size");
  AtomicInteger visits = new AtomicInteger();
  List<PlaybackRequest.Source> mutableSourceSize = new AbstractList<PlaybackRequest.Source>() {
   @Override public int size(){return visits.incrementAndGet()==1 ? 1 : Integer.MAX_VALUE;}
   @Override public PlaybackRequest.Source get(int i){return source;}
   @Override public Iterator<PlaybackRequest.Source> iterator(){throw new AssertionError("Unexpected untrusted playback iterator");}
  };
  PlaybackRequest b = new PlaybackRequest("p", media, null, mutableSourceSize, "s", Collections.emptyList(), Collections.emptyList());
  check(b.sources.size()==1 && visits.get()==1,"Repeated source size or custom iterator");
  Random r = new Random(20261109L);
  for(int i=0;i<50_000;i++) {
   int count=r.nextInt(51);
   List<PlaybackRequest.SkipSegment> safe = new ArrayList<>();
   for (int j=0;j<count;j++) safe.add(new PlaybackRequest.SkipSegment("seg"+j,"intro","intro",j*3,j*3+1));
   PlaybackRequest p = new PlaybackRequest("p",media,null,Collections.singletonList(source),"s",safe,Collections.emptyList());
   check(p.skipSegments.size()==count,"Normal playback segment preservation");
  }
  System.out.println("PLAYBACK_COLLECTION_ADMISSION_PASS checks="+assertions);
 }
}
