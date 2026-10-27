package com.evminsight.evm;

/** Thrown when calculation inputs violate the domain rules (for example completion above 100%). */
public class InvalidEvmInputException extends IllegalArgumentException {
    public InvalidEvmInputException(String message) {
        super(message);
    }
}
