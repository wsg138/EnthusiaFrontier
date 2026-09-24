package net.enthusia.frontier.application;

import java.util.List;

/** Outbound port for the durable frontier ledger. */
public interface FrontierRepository {
    void initialize() throws Exception;

    void applyBatch(List<FrontierMutation> mutations) throws Exception;

    FrontierStats stats() throws Exception;

    void close() throws Exception;
}
