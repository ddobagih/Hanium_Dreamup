package kr.co.hanium.dreamup.walksafe.inference.unknown;

import kr.co.hanium.dreamup.walksafe.inference.RgbLetterboxPreprocessor;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.TensorFlowLite;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.gpu.GpuDelegateFactory;

/** All native lifetime operations run on the dedicated service model owner. */
final class TfliteFastSamEngine implements FastSamEngine {
    static final class NativeReleaseFailure extends RuntimeException {
        NativeReleaseFailure(Throwable cause){super("FastSAM native release was not confirmed",cause);}
    }
    private final long owner=Thread.currentThread().getId();
    private final ByteBuffer input=ByteBuffer.allocateDirect(FastSamModelContract.INPUT_FLOATS*4).order(ByteOrder.nativeOrder());
    private final float[] inputScratch=new float[FastSamModelContract.INPUT_FLOATS];
    private final Map<Integer,Object> outputs=new LinkedHashMap<>();
    private ByteBuffer model;
    private Interpreter interpreter;
    private GpuDelegate delegate;
    private FastSamRuntimeInfo info;
    private boolean releaseUnconfirmed;

    TfliteFastSamEngine(Context context,FastSamRuntimeOptions options)throws Exception {
        long started=SystemClock.elapsedRealtimeNanos();
        try {
            deadline(options);
            try(AssetFileDescriptor descriptor=context.getAssets().openFd(FastSamModelContract.ASSET);
                FileInputStream stream=new FileInputStream(descriptor.getFileDescriptor())) {
                model=stream.getChannel().map(FileChannel.MapMode.READ_ONLY,descriptor.getStartOffset(),descriptor.getDeclaredLength());
            }
            if(!FastSamModelContract.SHA256.equals(sha256(model)))
                throw new IllegalArgumentException("FastSAM768 asset SHA256 mismatch");
            deadline(options);
            SerializationCache cache=prepareCache(context,options);
            Initialization initialized=initialize(options,cache,new NativeInitialization() {
                public void create(FastSamRuntimeOptions.Backend backend,SerializationCache configured) {
                    createNative(options,backend,configured);
                }
                public void release(){releaseNative();}
                public void checkDeadline()throws TimeoutException{deadline(options);}
            });
            deadline(options);
            if(interpreter.getInputTensorCount()!=1 || interpreter.getOutputTensorCount()!=2)
                throw new IllegalArgumentException("FastSAM768 tensor count mismatch");
            Tensor in=interpreter.getInputTensor(0),a=interpreter.getOutputTensor(0),b=interpreter.getOutputTensor(1);
            FastSamModelContract.validate(FastSamModelContract.SHA256,in.shape(),new int[][]{a.shape(),b.shape()},
                in.dataType()==DataType.FLOAT32 && a.dataType()==DataType.FLOAT32 && b.dataType()==DataType.FLOAT32);
            if(in.numBytes()!=FastSamModelContract.INPUT_FLOATS*4 || a.numBytes()!=FastSamModelContract.DETECTION_FLOATS*4
                    || b.numBytes()!=FastSamModelContract.PROTOTYPE_FLOATS*4)
                throw new IllegalArgumentException("FastSAM768 tensor byte count mismatch");
            outputs.put(0,ByteBuffer.allocateDirect(a.numBytes()).order(ByteOrder.nativeOrder()));
            outputs.put(1,ByteBuffer.allocateDirect(b.numBytes()).order(ByteOrder.nativeOrder()));
            info=new FastSamRuntimeInfo(options,initialized.actual,initialized.fallbackReason,owner,ms(started),
                cache.status,cache.token,cache.failureReason,initialized.gpuAttempts);
        } catch(Throwable failure) {
            try{close();}catch(Throwable closeFailure){failure.addSuppressed(closeFailure);throw new NativeReleaseFailure(failure);}
            if(releaseUnconfirmed)throw new NativeReleaseFailure(failure);
            throw failure;
        }
    }

    private void createNative(FastSamRuntimeOptions options,FastSamRuntimeOptions.Backend backend,
            SerializationCache cache) {
        Interpreter.Options nativeOptions=new Interpreter.Options().setNumThreads(options.numThreads)
            .setUseNNAPI(false).setUseXNNPACK(true);
        if(backend==FastSamRuntimeOptions.Backend.GPU) {
            delegate=new GpuDelegate(gpuOptions(options,cache));
            nativeOptions.addDelegate(delegate);
        }
        interpreter=new Interpreter(model,nativeOptions);
        interpreter.allocateTensors();
    }

    static GpuDelegateFactory.Options gpuOptions(FastSamRuntimeOptions options,SerializationCache cache) {
        GpuDelegateFactory.Options gpu=new GpuDelegateFactory.Options()
            .setPrecisionLossAllowed(options.gpuPrecisionLossAllowed)
            .setInferencePreference(GpuDelegateFactory.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED);
        if(cache!=null)gpu.setSerializationParams(cache.directory.getAbsolutePath(),cache.token);
        return gpu;
    }

    @Override public FastSamRuntimeInfo runtime(){return info;}
    @Override public Output invoke(int[] argb,int width,int height) {
        owner();if(interpreter==null)throw new IllegalStateException("FastSAM engine closed");
        long started=SystemClock.elapsedRealtimeNanos();
        RgbLetterboxPreprocessor.write(argb,width,height,FastSamModelContract.INPUT_SIZE,input,inputScratch);
        double preprocessing=ms(started);
        for(Object out:outputs.values())((ByteBuffer)out).clear();
        started=SystemClock.elapsedRealtimeNanos();
        interpreter.runForMultipleInputsOutputs(new Object[]{input},outputs);
        double inference=ms(started);started=SystemClock.elapsedRealtimeNanos();
        float[][] values=new float[2][];
        for(Map.Entry<Integer,Object> out:outputs.entrySet()) {
            ByteBuffer bytes=(ByteBuffer)out.getValue();float[] owned=new float[bytes.capacity()/4];
            ByteBuffer read=bytes.duplicate().order(ByteOrder.nativeOrder());
            read.clear();
            read.asFloatBuffer().get(owned);
            values[out.getKey()]=owned;
        }
        // The CPU decoder checks every detection/prototype float before producing any mask.
        return new Output(values,preprocessing,inference,ms(started));
    }
    @Override public void close(){owner();releaseNative();outputs.clear();model=null;}
    private void releaseNative() {
        Throwable failure=null;
        if(interpreter!=null){Interpreter owned=interpreter;interpreter=null;try{owned.close();}catch(Throwable error){failure=error;}}
        if(delegate!=null){GpuDelegate owned=delegate;delegate=null;try{owned.close();}catch(Throwable error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
        if(failure!=null){releaseUnconfirmed=true;throw new IllegalStateException("FastSAM native close failed",failure);}
    }
    private void owner(){if(Thread.currentThread().getId()!=owner)throw new IllegalStateException("FastSAM native owner changed");}
    private static void deadline(FastSamRuntimeOptions options)throws TimeoutException {
        if(SystemClock.elapsedRealtimeNanos()>=options.loadDeadlineElapsedNs)
            throw new TimeoutException("FastSAM load deadline exceeded; incomplete cold load is not unsupported-model evidence");
    }
    private static double ms(long started){return (SystemClock.elapsedRealtimeNanos()-started)/1e6;}
    private static String sha256(ByteBuffer model)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");ByteBuffer read=model.asReadOnlyBuffer();read.rewind();
        byte[] chunk=new byte[65536];while(read.hasRemaining()){int n=Math.min(chunk.length,read.remaining());read.get(chunk,0,n);digest.update(chunk,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));return hex.toString();
    }

    /** A configured cache is not evidence that the native runtime hit it. No caller supplies paths. */
    static final class SerializationCache {
        final File directory;
        final String token;
        String status,failureReason;
        SerializationCache(File directory,String token,String status,String failureReason) {
            this.directory=directory;this.token=token;this.status=status;this.failureReason=failureReason;
        }
    }
    private static SerializationCache prepareCache(Context context,FastSamRuntimeOptions options) {
        if(options.requestedBackend!=FastSamRuntimeOptions.Backend.GPU)
            return new SerializationCache(null,null,"not_applicable",null);
        if(!options.gpuSerializationCacheEnabled)return new SerializationCache(null,null,"disabled",null);
        String token=null;
        try {
            token=cacheToken(FastSamModelContract.SHA256,options.numThreads,options.gpuPrecisionLossAllowed,
                TensorFlowLite.runtimeVersion(),Build.FINGERPRINT,Build.HARDWARE,Build.SUPPORTED_ABIS,Build.VERSION.SDK_INT);
            return new SerializationCache(privateCacheDirectory(context.getCodeCacheDir(),token),token,"prepared",null);
        } catch(IOException|RuntimeException|LinkageError unavailable) {
            return new SerializationCache(null,token,"unavailable",failureSummary(unavailable));
        }
    }
    static File privateCacheDirectory(File codeCacheDir,String token)throws IOException {
        if(codeCacheDir==null || token==null || !token.matches("[a-f0-9]{64}"))
            throw new IOException("Private code cache/token unavailable");
        File parent=new File(codeCacheDir,"walksafe-fastsam-gpu-v1");
        File directory=new File(parent,token);
        if(!parent.getCanonicalFile().getParentFile().equals(codeCacheDir.getCanonicalFile())
                || !directory.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile())
                || (!(directory.isDirectory() || directory.mkdirs())) || !directory.isDirectory() || !directory.canWrite())
            throw new IOException("FastSAM GPU serialization directory unavailable");
        return directory;
    }
    static String cacheToken(String modelSha,int threads,boolean precision,String runtimeVersion,
            String fingerprint,String hardware,String[] abis,int sdkInt) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            String[] fields={"walksafe-fastsam-gpu-cache-v1",modelSha,
                "FLOAT32:[1,768,768,3]->FLOAT32:[1,37,12096],[1,192,192,32]",
                "com.google.ai.edge.litert:litert-gpu:1.4.0",runtimeVersion,
                "threads="+threads+";precisionLoss="+precision+";sustainedSpeed=1;quantized=true;backend=UNSET;nnapi=false;xnnpack=true",
                fingerprint,hardware,Integer.toString(sdkInt),Integer.toString(abis.length)};
            for(String field:fields)digestField(digest,field);
            for(String abi:abis)digestField(digest,abi);
            StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));return hex.toString();
        } catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static void digestField(MessageDigest digest,String field) {
        byte[] bytes=field.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
    }

    /** The same state machine is exercised with failing native-operation doubles in unit tests. */
    interface NativeInitialization {
        void create(FastSamRuntimeOptions.Backend backend,SerializationCache cache);
        void release();
        void checkDeadline()throws TimeoutException;
    }
    static final class Initialization {
        final FastSamRuntimeOptions.Backend actual;final String fallbackReason;final int gpuAttempts;
        Initialization(FastSamRuntimeOptions.Backend actual,String fallbackReason,int gpuAttempts) {
            this.actual=actual;this.fallbackReason=fallbackReason;this.gpuAttempts=gpuAttempts;
        }
    }
    static Initialization initialize(FastSamRuntimeOptions options,SerializationCache cache,
            NativeInitialization nativeOps)throws TimeoutException {
        nativeOps.checkDeadline();
        if(options.requestedBackend==FastSamRuntimeOptions.Backend.CPU) {
            nativeOps.create(FastSamRuntimeOptions.Backend.CPU,null);
            return new Initialization(FastSamRuntimeOptions.Backend.CPU,null,0);
        }
        int attempts=0;
        boolean serialized=cache.directory!=null;
        Throwable gpuFailure;
        try {
            if(serialized)cache.status="configured";
            attempts++;nativeOps.create(FastSamRuntimeOptions.Backend.GPU,serialized?cache:null);
            return new Initialization(FastSamRuntimeOptions.Backend.GPU,null,attempts);
        } catch(RuntimeException|LinkageError failure) {
            gpuFailure=failure;releaseFailedAttempt(nativeOps,failure);
        }
        if(serialized) {
            cache.status="bypassed_after_initialization_failure";cache.failureReason=failureSummary(gpuFailure);
            nativeOps.checkDeadline();
            try {
                attempts++;nativeOps.create(FastSamRuntimeOptions.Backend.GPU,null);
                return new Initialization(FastSamRuntimeOptions.Backend.GPU,null,attempts);
            } catch(RuntimeException|LinkageError retryFailure) {
                if(retryFailure!=gpuFailure)retryFailure.addSuppressed(gpuFailure);
                gpuFailure=retryFailure;releaseFailedAttempt(nativeOps,retryFailure);
            }
        }
        if(!options.allowCpuFallback) {
            if(gpuFailure instanceof RuntimeException)throw (RuntimeException)gpuFailure;
            throw (LinkageError)gpuFailure;
        }
        nativeOps.checkDeadline();
        try{nativeOps.create(FastSamRuntimeOptions.Backend.CPU,null);}
        catch(RuntimeException|LinkageError cpuFailure){if(cpuFailure!=gpuFailure)cpuFailure.addSuppressed(gpuFailure);throw cpuFailure;}
        return new Initialization(FastSamRuntimeOptions.Backend.CPU,failureSummary(gpuFailure),attempts);
    }
    private static void releaseFailedAttempt(NativeInitialization nativeOps,Throwable failure) {
        try{nativeOps.release();}
        catch(Throwable cleanupFailure){if(cleanupFailure!=failure)failure.addSuppressed(cleanupFailure);throw new NativeReleaseFailure(failure);}
    }
    private static String failureSummary(Throwable error){return error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage());}
}
