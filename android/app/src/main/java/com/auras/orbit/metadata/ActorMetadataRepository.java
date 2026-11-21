package com.auras.orbit.metadata;

/** Loads an actor profile and cross-media credits without depending on a stream provider. */
public interface ActorMetadataRepository {
    /** Returns null when the actor name does not match a TMDB person. */
    ActorMetadata load(String actorName, String contextTitle) throws Exception;
}
