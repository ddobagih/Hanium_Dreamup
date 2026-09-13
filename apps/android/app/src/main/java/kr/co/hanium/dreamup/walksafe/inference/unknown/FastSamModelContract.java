package kr.co.hanium.dreamup.walksafe.inference.unknown;

import java.util.Arrays;

/** Role-specific contract. The primary detector's equally sized tensor is not this input. */
public final class FastSamModelContract {
    public static final String MODEL_ID="fastsam_s_768";
    public static final String ASSET="models/fastsam_s_768_float32.tflite";
    public static final String SHA256="7e65b23c52bc4a78f45c7a26ac49a8b0a3f9fc8b8ba8c98ef40f2bf45f738609";
    public static final int INPUT_SIZE=768, INPUT_FLOATS=768*768*3;
    public static final int DETECTION_FLOATS=37*12096, PROTOTYPE_FLOATS=192*192*32;
    private FastSamModelContract() {}
    public static void validate(String sha,int[] input,int[][] outputs,boolean allFloat32) {
        if(!SHA256.equals(sha) || !allFloat32 || !Arrays.equals(input,new int[]{1,768,768,3})
                || outputs.length!=2 || !Arrays.equals(outputs[0],new int[]{1,37,12096})
                || !Arrays.equals(outputs[1],new int[]{1,192,192,32}))
            throw new IllegalArgumentException("FastSAM768 model/hash/tensor contract mismatch");
    }
}
