package com.evminsight.evm;

/** Explicit calculation options. Nothing is switched on silently (FR-15). */
public record EvmOptions(boolean compositeEac) {
    public static EvmOptions defaults() {
        return new EvmOptions(false);
    }
}
