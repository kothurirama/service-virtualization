package com.svp.engine.model;

/** Diagnostic returned on a miss: a stub whose path matched but whose constraint failed. */
public record NearMiss(String stubId, String failedRule, String detail) {
}
