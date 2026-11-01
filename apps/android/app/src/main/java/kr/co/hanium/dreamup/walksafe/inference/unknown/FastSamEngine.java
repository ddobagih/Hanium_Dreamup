package kr.co.hanium.dreamup.walksafe.inference.unknown;

/** Narrow model boundary for tests and the service owner; outputs are transferred, never borrowed. */
interface FastSamEngine extends AutoCloseable {
    interface Factory {
        FastSamEngine create() throws Exception;
        /** Present before remote OPEN can fail; null preserves the synchronous local-close contract. */
        default java.util.concurrent.CompletableFuture<Void> actualReleasedFuture(){return null;}
        /** Control-only cancellation while create is pending; never treats interruption as release. */
        default void cancelPendingLoad(){}
        default boolean supportsTransfer(){return false;}
        default void validateTransferredAttachment(){throw new UnsupportedOperationException("Not a transferred factory");}
        default void activateTransferred(){throw new UnsupportedOperationException("Not a transferred factory");}
        default boolean canAdmit(){return true;}
    }
    FastSamRuntimeInfo runtime();
    Output invoke(int[] ownedArgb,int width,int height);
    default Output invoke(int[] ownedArgb,int width,int height,FastSamFrameToken token){return invoke(ownedArgb,width,height);}
    default FastSamTransferredModel detachForTransfer(){throw new UnsupportedOperationException("FastSAM engine cannot transfer");}
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
