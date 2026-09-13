package kr.co.hanium.dreamup.walksafe.inference.unknown;

/** Narrow model boundary for tests and the service owner; outputs are transferred, never borrowed. */
interface FastSamEngine extends AutoCloseable {
    interface Factory { FastSamEngine create() throws Exception; }
    FastSamRuntimeInfo runtime();
    Output invoke(int[] ownedArgb,int width,int height);
    @Override void close();
    final class Output {
        final float[][] ownedTensors;
        final double preprocessingMs,inferenceMs,outputReadMs;
        Output(float[][] tensors,double preprocessingMs,double inferenceMs,double outputReadMs) {
            ownedTensors=tensors;this.preprocessingMs=preprocessingMs;
            this.inferenceMs=inferenceMs;this.outputReadMs=outputReadMs;
        }
    }
}
