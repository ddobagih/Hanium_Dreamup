package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.Arrays;

/** Role-specific contract. The primary detector's equally sized tensor is not this input. */
public final class FastSamModelContract {
    public static final String MODEL_ID="fastsam_s_768";
    public static final String ASSET="models/fastsam_s_768_float32.tflite";
    public static final String SHA256="7e65b23c52bc4a78f45c7a26ac49a8b0a3f9fc8b8ba8c98ef40f2bf45f738609";
    public static final int INPUT_SIZE=768, INPUT_FLOATS=768*768*3;
    public static final int DETECTION_FLOATS=37*12096, PROTOTYPE_FLOATS=192*192*32;
    public enum NativeOutputFailureReason { NON_FINITE_OUTPUT, OUTPUT_SHAPE_MISMATCH, INVALID_NATIVE_OUTPUT }
    /** Only the owned raw-output/decoder boundary creates this classification. */
    public static final class NativeOutputException extends IllegalArgumentException {
        public final NativeOutputFailureReason reason;
        public NativeOutputException(NativeOutputFailureReason reason,String message){super(message);this.reason=java.util.Objects.requireNonNull(reason);}
    }
    private FastSamModelContract() {}
    public static void validateRawOutputShape(float[][] outputs){
        if(outputs==null || outputs.length!=2)throw new NativeOutputException(
            NativeOutputFailureReason.OUTPUT_SHAPE_MISMATCH,"Expected exactly two native FastSAM outputs");
        validateRawOutputShape(outputs[0],outputs[1]);
    }
    public static void validateRawOutputShape(float[] detections,float[] prototypes){
        if(detections==null || detections.length!=DETECTION_FLOATS || prototypes==null || prototypes.length!=PROTOTYPE_FLOATS)
            throw new NativeOutputException(NativeOutputFailureReason.OUTPUT_SHAPE_MISMATCH,"Native FastSAM768 output shape differs");
    }
    public static void validateFinite(float[] values){
        for(float value:values)if(!(value<=Float.MAX_VALUE && value>=-Float.MAX_VALUE))
            throw new NativeOutputException(NativeOutputFailureReason.NON_FINITE_OUTPUT,"Non-finite native FastSAM output");
    }
    public static void validate(String sha,int[] input,int[][] outputs,boolean allFloat32) {
        if(!SHA256.equals(sha) || !allFloat32 || !Arrays.equals(input,new int[]{1,768,768,3})
                || outputs.length!=2 || !Arrays.equals(outputs[0],new int[]{1,37,12096})
                || !Arrays.equals(outputs[1],new int[]{1,192,192,32}))
            throw new IllegalArgumentException("FastSAM768 model/hash/tensor contract mismatch");
    }
}
