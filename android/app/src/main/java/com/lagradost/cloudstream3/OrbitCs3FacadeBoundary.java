package com.lagradost.cloudstream3;

/** Signals that a CS3 facade method remains outside Orbit's supported service surface. Implemented compatibility bridges do not call this helper. The pinned contracts are pre-release 5024ace2922a148d38df2a816b483b529789cf58 and stable v4.8.0 / 3796406e636356f556c16e04e1da4e37d6a19038. */
public final class OrbitCs3FacadeBoundary { private OrbitCs3FacadeBoundary() {} public static UnsupportedOperationException unimplemented(String symbol) { return new UnsupportedOperationException("Orbit CS3 descriptor facade has no service implementation: " + symbol); } }
