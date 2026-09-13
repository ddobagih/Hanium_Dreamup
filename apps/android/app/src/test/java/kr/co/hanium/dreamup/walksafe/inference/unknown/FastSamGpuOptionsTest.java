package kr.co.hanium.dreamup.walksafe.inference.unknown;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeoutException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.tensorflow.lite.gpu.GpuDelegateFactory;

public final class FastSamGpuOptionsTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static FastSamRuntimeOptions options(boolean fallback,boolean precision,boolean cache) {
        return new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU,1,fallback,10000000000L,800,false,precision,cache);
    }
    private static TfliteFastSamEngine.SerializationCache cache(boolean enabled) {
        return new TfliteFastSamEngine.SerializationCache(enabled?new File("private/cache"):null,enabled?"a".repeat(64):null,enabled?"prepared":"disabled",null);
    }
    private static final class Native implements TfliteFastSamEngine.NativeInitialization {
        final List<String> events=new ArrayList<>();final RuntimeException gpu1=new IllegalStateException("cached broken"),gpu2=new IllegalStateException("gpu unavailable");
        int failures,gpuAttempts,deadlineCalls,expireAt;boolean releaseFails,cpuFails,oom;
        public void create(FastSamRuntimeOptions.Backend backend,TfliteFastSamEngine.SerializationCache cache) {
            events.add(backend.name()+":"+(cache==null?"plain":"cached"));
            if(backend==FastSamRuntimeOptions.Backend.GPU){gpuAttempts++;if(oom)throw new OutOfMemoryError("OOM");if(gpuAttempts<=failures)throw gpuAttempts==1?gpu1:gpu2;}
            else if(cpuFails)throw new IllegalArgumentException("CPU failed");
        }
        public void release(){events.add("release");if(releaseFails)throw new IllegalStateException("native close failed");}
        public void checkDeadline()throws TimeoutException{deadlineCalls++;if(expireAt==deadlineCalls)throw new TimeoutException("expired");}
    }
    @Test public void legacyConstructorRemainsStrictAndCacheOff() {
        FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.GPU,1,false,1000,800,false);
        assertFalse(o.gpuPrecisionLossAllowed);assertFalse(o.gpuSerializationCacheEnabled);
        GpuDelegateFactory.Options nativeOptions=TfliteFastSamEngine.gpuOptions(o,null);
        assertFalse(nativeOptions.isPrecisionLossAllowed());assertNull(nativeOptions.getSerializationDir());
        assertEquals(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED,nativeOptions.getInferencePreference());
    }
    @Test public void explicitPrecisionAndCacheUseExactConfiguredNativeOptions() {
        FastSamRuntimeOptions o=options(false,true,true);TfliteFastSamEngine.SerializationCache cache=cache(true);
        GpuDelegateFactory.Options nativeOptions=TfliteFastSamEngine.gpuOptions(o,cache);
        assertTrue(nativeOptions.isPrecisionLossAllowed());assertEquals(cache.directory.getAbsolutePath(),nativeOptions.getSerializationDir());assertEquals(cache.token,nativeOptions.getModelToken());
        FastSamRuntimeInfo gpu=new FastSamRuntimeInfo(o,FastSamRuntimeOptions.Backend.GPU,null,1,2,"configured",cache.token,null,1);
        assertTrue(gpu.gpuPrecisionLossRequested&&gpu.gpuPrecisionLossAllowed&&gpu.gpuSerializationCacheConfigured);assertEquals(1,gpu.gpuInitializationAttempts);
        FastSamRuntimeInfo cpu=new FastSamRuntimeInfo(o,FastSamRuntimeOptions.Backend.CPU,"gpu failed",1,2,"bypassed_after_initialization_failure",cache.token,"cache failed",2);
        assertTrue(cpu.gpuPrecisionLossRequested&&cpu.gpuSerializationCacheRequested&&cpu.fallbackUsed);assertFalse(cpu.gpuPrecisionLossAllowed||cpu.gpuDelegateAttached||cpu.gpuSerializationCacheConfigured);
    }
    @Test public void cacheBindingSeparatesEveryRuntimeDeviceAndOptionInput() {
        String sha=FastSamModelContract.SHA256;String base=TfliteFastSamEngine.cacheToken(sha,1,false,"runtime","fingerprint","hw",new String[]{"arm64-v8a"},36);
        assertEquals(base,TfliteFastSamEngine.cacheToken(sha,1,false,"runtime","fingerprint","hw",new String[]{"arm64-v8a"},36));assertTrue(base.matches("[a-f0-9]{64}"));
        List<String> variants=Arrays.asList(
            TfliteFastSamEngine.cacheToken("primaryModel",1,false,"runtime","fingerprint","hw",new String[]{"arm64-v8a"},36),
            TfliteFastSamEngine.cacheToken(sha,2,false,"runtime","fingerprint","hw",new String[]{"arm64-v8a"},36),
            TfliteFastSamEngine.cacheToken(sha,1,true,"runtime","fingerprint","hw",new String[]{"arm64-v8a"},36),
            TfliteFastSamEngine.cacheToken(sha,1,false,"runtime2","fingerprint","hw",new String[]{"arm64-v8a"},36),
            TfliteFastSamEngine.cacheToken(sha,1,false,"runtime","fingerprint2","hw",new String[]{"arm64-v8a"},36),
            TfliteFastSamEngine.cacheToken(sha,1,false,"runtime","fingerprint","hw2",new String[]{"arm64-v8a"},36),
            TfliteFastSamEngine.cacheToken(sha,1,false,"runtime","fingerprint","hw",new String[]{"armeabi-v7a"},36),
            TfliteFastSamEngine.cacheToken(sha,1,false,"runtime","fingerprint","hw",new String[]{"arm64-v8a"},35));
        for(String v:variants)assertNotEquals(base,v);assertEquals(variants.size(),new HashSet<>(variants).size());
        assertNotEquals(TfliteFastSamEngine.cacheToken(sha,1,false,"ab","c","h",new String[]{"x"},36),TfliteFastSamEngine.cacheToken(sha,1,false,"a","bc","h",new String[]{"x"},36));
    }
    @Test public void privateDirectoryHasDistinctTokensAndPreservesExistingFiles()throws Exception {
        File root=temp.newFolder("code-cache");File a=TfliteFastSamEngine.privateCacheDirectory(root,"a".repeat(64));File sentinel=new File(a,"native-cache");Files.write(sentinel.toPath(),new byte[]{1,2,3});
        assertEquals(a,TfliteFastSamEngine.privateCacheDirectory(root,"a".repeat(64)));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(sentinel.toPath()));
        assertNotEquals(a,TfliteFastSamEngine.privateCacheDirectory(root,"b".repeat(64)));
        assertThrows(java.io.IOException.class,()->TfliteFastSamEngine.privateCacheDirectory(root,"../escape"));
        assertThrows(java.io.IOException.class,()->TfliteFastSamEngine.privateCacheDirectory(temp.newFile("blocked"),"a".repeat(64)));
    }
    @Test public void validCacheRequiresOnlyOneNativeAttempt()throws Exception {
        Native n=new Native();TfliteFastSamEngine.SerializationCache c=cache(true);TfliteFastSamEngine.Initialization r=TfliteFastSamEngine.initialize(options(false,false,true),c,n);
        assertEquals(Collections.singletonList("GPU:cached"),n.events);assertEquals("configured",c.status);assertEquals(1,r.gpuAttempts);assertNull(r.fallbackReason);
    }
    @Test public void corruptCacheIsReleasedBeforeExactlyOneUncachedGpuRetry()throws Exception {
        Native n=new Native();n.failures=1;TfliteFastSamEngine.SerializationCache c=cache(true);TfliteFastSamEngine.Initialization r=TfliteFastSamEngine.initialize(options(false,false,true),c,n);
        assertEquals(Arrays.asList("GPU:cached","release","GPU:plain"),n.events);assertEquals("bypassed_after_initialization_failure",c.status);assertTrue(c.failureReason.contains("cached broken"));assertEquals(2,r.gpuAttempts);assertEquals(FastSamRuntimeOptions.Backend.GPU,r.actual);
    }
    @Test public void failedUncachedRetryMayUseOnlyExplicitCpuFallback()throws Exception {
        Native n=new Native();n.failures=2;TfliteFastSamEngine.Initialization r=TfliteFastSamEngine.initialize(options(true,false,true),cache(true),n);
        assertEquals(Arrays.asList("GPU:cached","release","GPU:plain","release","CPU:plain"),n.events);assertEquals(FastSamRuntimeOptions.Backend.CPU,r.actual);assertEquals(2,r.gpuAttempts);assertTrue(r.fallbackReason.contains("gpu unavailable"));assertSame(n.gpu1,n.gpu2.getSuppressed()[0]);
        Native strict=new Native();strict.failures=2;assertSame(strict.gpu2,assertThrows(IllegalStateException.class,()->TfliteFastSamEngine.initialize(options(false,false,true),cache(true),strict)));assertEquals(4,strict.events.size());
    }
    @Test public void noSerializationMeansNoExtraGpuRetry()throws Exception {
        Native n=new Native();n.failures=1;TfliteFastSamEngine.Initialization r=TfliteFastSamEngine.initialize(options(true,false,false),cache(false),n);
        assertEquals(Arrays.asList("GPU:plain","release","CPU:plain"),n.events);assertEquals(1,r.gpuAttempts);
    }
    @Test public void failedReleasePreventsEveryRetryAndCpuFallback() {
        Native n=new Native();n.failures=1;n.releaseFails=true;
        TfliteFastSamEngine.NativeReleaseFailure error=assertThrows(TfliteFastSamEngine.NativeReleaseFailure.class,()->TfliteFastSamEngine.initialize(options(true,false,true),cache(true),n));
        assertSame(n.gpu1,error.getCause());assertEquals(Arrays.asList("GPU:cached","release"),n.events);assertEquals(1,n.gpu1.getSuppressed().length);
    }
    @Test public void deadlineAndOutOfMemoryNeverStartRecoveryAttempt() {
        Native expired=new Native();expired.failures=1;expired.expireAt=2;assertThrows(TimeoutException.class,()->TfliteFastSamEngine.initialize(options(true,false,true),cache(true),expired));assertEquals(Arrays.asList("GPU:cached","release"),expired.events);
        Native oom=new Native();oom.oom=true;assertThrows(OutOfMemoryError.class,()->TfliteFastSamEngine.initialize(options(true,false,true),cache(true),oom));assertEquals(Collections.singletonList("GPU:cached"),oom.events);
    }
    @Test public void cpuFailureRetainsGpuCauseAndCpuOnlyHasNoGpuAttempt()throws Exception {
        Native failed=new Native();failed.failures=1;failed.cpuFails=true;IllegalArgumentException error=assertThrows(IllegalArgumentException.class,()->TfliteFastSamEngine.initialize(options(true,false,false),cache(false),failed));assertSame(failed.gpu1,error.getSuppressed()[0]);
        Native cpu=new Native();FastSamRuntimeOptions o=new FastSamRuntimeOptions(FastSamRuntimeOptions.Backend.CPU,1,false,1000,800,false,true,true);TfliteFastSamEngine.Initialization r=TfliteFastSamEngine.initialize(o,cache(false),cpu);assertEquals(Collections.singletonList("CPU:plain"),cpu.events);assertEquals(0,r.gpuAttempts);
    }
}
