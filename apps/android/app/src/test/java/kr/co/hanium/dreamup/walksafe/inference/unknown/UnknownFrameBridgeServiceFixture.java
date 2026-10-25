package kr.co.hanium.dreamup.walksafe.inference.unknown;

/** Package access for an actual unopened service. No engine, Image, model thread or native SDK is started. */
public final class UnknownFrameBridgeServiceFixture {
    public static <T> FastSamRuntimeService<T> unopened() {
        return new FastSamRuntimeService<>(1L, new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,900000000L,800,false),
            result -> {throw new AssertionError("unopened service result");},
            () -> {throw new AssertionError("unopened engine factory");}, () -> 100000000L);
    }
}
