package audit;

import com.auras.orbit.providers.*;
import java.util.*;

/** Adversarial Home/Search provider fan-out budget checks (full production helper). */
public final class ProviderResultsBudgetRegression {
    private static long checks;
    private static void check(boolean value,String why) {
        checks++;
        if (!value)throw new AssertionError(why+"; check="+checks);
    }
    private static final ProviderDescriptor P=new ProviderDescriptor("source", "Source",true,true,true,true,true);
    private static final MediaItem MOVIE=new MediaItem("film", "Film",MediaKind.MOVIE,"","","","");
    private static final MediaItem SERIES=new MediaItem("series", "Series",MediaKind.SERIES,"","","","");
    private static final MediaItem UNKNOWN=new MediaItem("unknown", "Unknown",MediaKind.UNKNOWN,"","","","");
    private static final MediaItem LIVE=new MediaItem("live", "Live",MediaKind.LIVE,"","","","");
    private static final MediaItem[] KINDS={MOVIE,SERIES,UNKNOWN,LIVE,null};
    public static void main(String[] args) {
        List<MediaItem> huge=new ArrayList<>(100_000);
        for(int i=0;i<100_000;i++)huge.add(KINDS[i%KINDS.length]);
        for(SearchType type:SearchType.values()) {
            try {
                ProviderResultBudget.search(P,huge,type);
                throw new AssertionError("Huge search must fail explicitly, not silently truncate");
            } catch (IllegalArgumentException expected) { check(true,"oversized search diagnostic"); }
            List<AttributedSearchHit> hits=ProviderResultBudget.search(P,huge.subList(0,1000),type);
            check(hits.size()<=ProviderResultBudget.MAX_SEARCH_ITEMS,"search budget");
            check(hits.size()>0,"valid results preserved");
            check(hits.get(0).provider==P,"provider identity preserved");
            for(AttributedSearchHit hit:hits) {
                check(hit.item!=null,"null input skipped");
                check(type==SearchType.ALL ||
                    (type==SearchType.MOVIES?hit.item.kind==MediaKind.MOVIE:hit.item.kind==MediaKind.SERIES),
                    "type filter matches coordinator contract");
            }
            try{hits.clear();throw new AssertionError("mutable result");}catch(UnsupportedOperationException pass){check(true,"immutable");}
        }
        List<HomeSection> home=new ArrayList<>();
        for(int i=0;i<200;i++)home.add(new HomeSection("s"+i,"Shelf "+i,huge.subList(0,256),"next"));
        List<AttributedHomeSection> shelves=ProviderResultBudget.home(P,2,home);
        check(shelves.size()==200,"normal Home shelves retained");
        for(int i=0;i<shelves.size();i++) {
            AttributedHomeSection shelf=shelves.get(i);
            check(shelf.provider==P&&shelf.sourceOrder==2,"shelf attribution");
            check(shelf.section.items.size()==256,"Home shelf not truncated");
            check(shelf.section.id.equals("s"+i),"provider ordering preserved");
        }
        try{shelves.clear();throw new AssertionError("mutable shelves");}catch(UnsupportedOperationException pass){check(true,"shelf immutable");}
        Random rnd=new Random(0x55cc021290L);
        for(int i=0;i<200_000;i++) {
            int length=rnd.nextInt(1700), start=rnd.nextInt(50);
            List<MediaItem> source=huge.subList(start,start+length);
            SearchType type=SearchType.values()[rnd.nextInt(3)];
            List<AttributedSearchHit> results=ProviderResultBudget.search(P,source,type);
            int scan=source.size();
            int expected=0;
            for(int j=0;j<scan;j++) {
                MediaItem m=source.get(j);
                if(m==null)continue;
                if(type==SearchType.MOVIES && m.kind!=MediaKind.MOVIE)continue;
                if(type==SearchType.SERIES && m.kind!=MediaKind.SERIES)continue;
                expected++;
            }
            check(results.size()==expected,"randomized valid input output count");
            check(results.size()<=ProviderResultBudget.MAX_SEARCH_ITEMS,"large search result bounded");
            check(results.isEmpty()||results.get(0).provider==P,"attribution consistent");
        }
        for(int i=0;i<50_000;i++) {
            int count=rnd.nextInt(200);
            List<HomeSection> chosen=home.subList(0,count);
            List<AttributedHomeSection> b=ProviderResultBudget.home(P,0,chosen);
            check(b.size()==count,"normal shelves retained");
            check(b.isEmpty()||b.get(0).section.items.size()==256,"Home records preserved");
        }
        List<HomeSection> tooManyShelves=Collections.nCopies(
                ProviderResultBudget.MAX_HOME_SECTIONS + 1,
                new HomeSection("overflow","Overflow", Collections.emptyList(), ""));
        try { ProviderResultBudget.home(P,0,tooManyShelves);
              throw new AssertionError("Home limit must be a visible failure"); }
        catch (IllegalArgumentException expected) { check(true,"oversized Home diagnostic"); }
        System.out.println("ProviderResultsBudgetRegression PASS checks="+checks);
    }
}
