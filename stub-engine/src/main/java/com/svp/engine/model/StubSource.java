package com.svp.engine.model;

/** Where a stub came from. Used for governance (e.g. recorded stubs may never overwrite hand-written ones). */
public enum StubSource {
    MANUAL,
    SEED,
    RECORDED
}
