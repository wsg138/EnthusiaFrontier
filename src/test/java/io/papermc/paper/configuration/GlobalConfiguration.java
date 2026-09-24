package io.papermc.paper.configuration;

/** Test-only stand-in for the Paper server-internal configuration reflected by the adapter. */
public final class GlobalConfiguration {
    private static GlobalConfiguration instance = new GlobalConfiguration();

    public ChunkLoadingBasic chunkLoadingBasic = new ChunkLoadingBasic();
    public ChunkLoadingAdvanced chunkLoadingAdvanced = new ChunkLoadingAdvanced();

    public static GlobalConfiguration get() {
        return instance;
    }

    public static void set(GlobalConfiguration value) {
        instance = value;
    }

    public static final class ChunkLoadingBasic {
        public double playerMaxChunkGenerateRate = -1.0;
    }

    public static final class ChunkLoadingAdvanced {
        public int playerMaxConcurrentChunkGenerates;
    }
}
