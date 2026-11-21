package com.auras.orbit.providers;
import java.util.List; import java.util.Map;
public class ProviderLinkCandidate {
 public String uri,type,referer; public int quality; public Map<String,String> headers; public List<AudioTrackCandidate> audioTracks;
 public Cs3PlaybackHooks cs3PlaybackHooks;
 public static class AudioTrackCandidate {public String id,uri; public Map<String,String> headers;}
}
