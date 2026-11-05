package kr.co.hanium.dreamup.walksafe.inference;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** DEBUG metadata boundary: never returns messages, paths, class names or stack frames. */
public final class NativeGpuDiagnostic {
    private NativeGpuDiagnostic() {}
    public enum Role { PRIMARY, AUXILIARY }
    public enum Stage { OPEN, COMPATIBILITY, DELEGATE_INIT, INTERPRETER_INIT, ALLOCATE_TENSORS }
    private enum Category { UNKNOWN, JNI_LIBRARY, API_LINKAGE, REFLECTION, OUT_OF_MEMORY, RESOURCE,
        THREAD_AFFINITY, OPENCL_LOAD, EGL_CONTEXT, GPU_PROGRAM_BUILD, GPU_DEVICE_CONTEXT, OP_UNSUPPORTED, TENSOR_SHAPE, GPU_UNSUPPORTED,
        ILLEGAL_STATE, ILLEGAL_ARGUMENT, SECURITY, OTHER }
    public static final class Snapshot {
        private final Role role; private final Stage stage;
        private final boolean gpu, serialized, ownerMatches; private final String summary;
        private Snapshot(Role role, Stage stage, boolean gpu, boolean serialized, boolean ownerMatches, Throwable error) {
            this.role=role;this.stage=stage;this.gpu=gpu;this.serialized=serialized;this.ownerMatches=ownerMatches;
            this.summary=summarize(error);
        }
        @Override public String toString() {
            return "event=NATIVE_GPU_INIT role="+role+" stage="+stage+" gpu="+gpu+
                " serialized="+serialized+" owner_matches="+ownerMatches+" categories="+summary;
        }
    }
    /** Observe before IPC/cache wrapping; preserve the exact throw and native lifetime ordering. */
    public static <T> T measure(Role role, Stage stage, boolean gpu, boolean serialized, boolean ownerMatches,
            Supplier<T> operation, Consumer<Snapshot> sink) {
        try { return operation.get(); }
        catch(RuntimeException|Error original) {
            try { sink.accept(new Snapshot(role,stage,gpu,serialized,ownerMatches,original)); }
            catch(Throwable ignored) { /* diagnostics must not replace a native failure */ }
            throw original;
        }
    }
    public static String summarize(Throwable original) {
        EnumSet<Category> categories=EnumSet.noneOf(Category.class);
        IdentityHashMap<Throwable,Boolean> seen=new IdentityHashMap<>();
        inspect(original,categories,seen,0);
        if(categories.isEmpty())categories.add(Category.UNKNOWN);
        return categories.toString();
    }
    private static void inspect(Throwable t,EnumSet<Category> out,IdentityHashMap<Throwable,Boolean> seen,int depth) {
        if(t==null || depth>=8 || seen.size()>=16 || seen.put(t,Boolean.TRUE)!=null)return;
        if(t instanceof UnsatisfiedLinkError)out.add(Category.JNI_LIBRARY);
        else if(t instanceof LinkageError)out.add(Category.API_LINKAGE);
        else if(t instanceof ReflectiveOperationException)out.add(Category.REFLECTION);
        else if(t instanceof OutOfMemoryError)out.add(Category.OUT_OF_MEMORY);
        else if(t instanceof IllegalStateException)out.add(Category.ILLEGAL_STATE);
        else if(t instanceof IllegalArgumentException)out.add(Category.ILLEGAL_ARGUMENT);
        else if(t instanceof SecurityException)out.add(Category.SECURITY);
        else out.add(Category.OTHER);
        // Messages are inspected in memory only; output is exclusively fixed enum constants.
        String raw=t.getMessage();String m=raw==null?"":raw.substring(0,Math.min(raw.length(),8192)).toLowerCase(Locale.ROOT);
        boolean recognized=false;
        if(m.contains("libtensorflowlite_gpu_jni") || m.contains("no implementation found for")){out.add(Category.JNI_LIBRARY);recognized=true;}
        if(m.contains("same thread") || m.contains("different thread")){out.add(Category.THREAD_AFFINITY);recognized=true;}
        if(m.contains("cl_out_of") || m.contains("out of resources") || m.contains("failed to allocate") || m.contains("out of memory")){out.add(Category.RESOURCE);recognized=true;}
        if(m.contains("opencl") && (m.contains("not loaded") || m.contains("dlopen") || m.contains("not found") || m.contains("can not open"))){out.add(Category.OPENCL_LOAD);recognized=true;}
        if((m.contains("egl") || m.contains("opengl")) && (m.contains("context") || m.contains("initialize"))){out.add(Category.EGL_CONTEXT);recognized=true;}
        if(m.contains("unsupported op") || m.contains("not supported op") || m.contains("not supported by this delegate") || m.contains("not supported on gpu") || m.contains("not supported by gpu delegate") || m.contains("not supported by tflite gpu delegate")){out.add(Category.OP_UNSUPPORTED);recognized=true;}
        if((m.contains("shape") || m.contains("dimension")) && (m.contains("invalid") || m.contains("mismatch") || m.contains("unsupported") || m.contains("not supported"))){out.add(Category.TENSOR_SHAPE);recognized=true;}
        if(m.contains("gpu delegate unsupported on this device")){out.add(Category.GPU_UNSUPPORTED);recognized=true;}
        if(m.contains("failed to create compute program") || m.contains("failed to create program") || m.contains("cl_build_program_failure")){out.add(Category.GPU_PROGRAM_BUILD);recognized=true;}
        if(m.contains("failed to create a compute context") || m.contains("cl_device_not_found")){out.add(Category.GPU_DEVICE_CONTEXT);recognized=true;}
        if(!recognized)out.add(Category.UNKNOWN);
        inspect(t.getCause(),out,seen,depth+1);
        Throwable[] suppressed=t.getSuppressed();for(int i=0;i<Math.min(4,suppressed.length);i++)inspect(suppressed[i],out,seen,depth+1);
    }
}
