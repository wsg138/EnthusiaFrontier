package net.enthusia.frontier.domain;

/** Determines whether Frontier manages a chunk outside the permanent square core. */
public record CoreBoundaryPolicy(int radiusBlocks) {
    private static final int CHUNK_SIZE = 16;

    public CoreBoundaryPolicy {
        if (radiusBlocks < 0) {
            throw new IllegalArgumentException("radiusBlocks must be >= 0");
        }
    }

    /**
     * Returns true when a chunk is managed by Frontier.
     * A zero radius intentionally means there is no permanent core.
     * For positive radii any chunk intersecting the core is conservatively permanent.
     */
    public boolean isManaged(ChunkKey key) {
        if (radiusBlocks == 0) {
            return true;
        }

        long minX = (long) key.x() * CHUNK_SIZE;
        long minZ = (long) key.z() * CHUNK_SIZE;
        long maxX = minX + CHUNK_SIZE - 1L;
        long maxZ = minZ + CHUNK_SIZE - 1L;
        long radius = radiusBlocks;

        boolean intersectsX = maxX >= -radius && minX <= radius;
        boolean intersectsZ = maxZ >= -radius && minZ <= radius;
        return !(intersectsX && intersectsZ);
    }
}
