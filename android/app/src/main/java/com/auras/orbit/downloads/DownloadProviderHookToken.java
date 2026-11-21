package com.auras.orbit.downloads;

/**
 * Bounded, data-only identity needed to reattach a CS3 extractor verifier to one saved link.
 * This value contains no provider, plugin, classloader, interceptor, or executable object.
 */
public final class DownloadProviderHookToken {
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_FIELD_LENGTH = 16_384;

    public final int version;
    public final String profileId;
    public final String pluginId;
    public final String providerId;
    public final int registrationIndex;
    public final String apiClassName;
    /** Captured during initial resolution; restore code must not call plugin getters to rebuild it. */
    public final String apiName;
    public final String pluginArchiveSha256;
    public final String sourceId;
    public final String sourceUrlSha256;
    /** Opaque CS3 verifier payload; encrypted before it reaches queue storage. */
    public final String extractorData;

    public DownloadProviderHookToken(int version, String profileId, String pluginId,
            String providerId, int registrationIndex, String apiClassName, String apiName,
            String pluginArchiveSha256, String sourceId, String sourceUrlSha256,
            String extractorData) {
        if (version != CURRENT_VERSION) throw new IllegalArgumentException("Unsupported download hook token version.");
        this.version = version;
        this.profileId = required(profileId, "profile ID", 128);
        this.pluginId = required(pluginId, "plugin ID", 512);
        this.providerId = required(providerId, "provider ID", 512);
        if (registrationIndex < 0) throw new IllegalArgumentException("Invalid provider registration identity.");
        this.registrationIndex = registrationIndex;
        this.apiClassName = required(apiClassName, "provider API class", 512);
        this.apiName = required(apiName, "provider API name", 256);
        this.pluginArchiveSha256 = requiredSha256(pluginArchiveSha256, "plugin archive hash");
        this.sourceId = required(sourceId, "source ID", 4096);
        this.sourceUrlSha256 = requiredSha256(sourceUrlSha256, "source URL hash");
        this.extractorData = requiredOpaque(extractorData, "extractor verifier data", MAX_FIELD_LENGTH);
    }

    private static String required(String value, String label, int maximum) {
        if (value == null || value.trim().isEmpty() || value.length() > maximum) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        String result = value.trim();
        for (int index = 0; index < result.length(); index++) {
            if (Character.isISOControl(result.charAt(index))) {
                throw new IllegalArgumentException("Invalid " + label + ".");
            }
        }
        return result;
    }

    private static String requiredSha256(String value, String label) {
        if (value == null || value.length() != 64) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (!((ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f'))) {
                throw new IllegalArgumentException("Invalid " + label + ".");
            }
        }
        return value;
    }

    private static String requiredOpaque(String value, String label, int maximum) {
        if (value == null || value.length() > maximum) {
            throw new IllegalArgumentException("Invalid " + label + ".");
        }
        return value;
    }
}
