package kr.co.hanium.dreamup.walksafe.depth.unknown;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure Java, depth-grid sampling of frozen masks. Single-threaded/session-scoped.
 * No velocity, temporal smoothing, collision classification, or voice behavior.
 */
public final class MaskDepthEstimator {
    public static final String POLICY_ID = "MASK_DEPTH_COMPONENT_SCOPE_V2_FIXED_20260909";
    public enum Status { KNOWN, PARTIAL, UNKNOWN }
    public enum Scope { ALL_COMPONENTS, DOMINANT_COMPONENT, NONE }

    public record FrameIdentity(String sessionId, String frameId, long cameraTimestampNs, String clockDomain) {}
    public record Intrinsics(int imageWidth, int imageHeight, double fx, double fy, double cx, double cy) {
        boolean validFor(int w, int h) {
            return imageWidth == w && imageHeight == h && finite(fx, fy, cx, cy) && fx > 0 && fy > 0;
        }
    }

    /** Pixel-edge coordinates; default membership samples floor(x),floor(y).
     * Implementations MUST be immutable for the lifetime of an association call.
     * Bounds enclose all foreground and are clipped to the original RGB image.
     */
    public interface ImageMask {
        int imageWidth(); int imageHeight();
        int minX(); int minY(); int maxXExclusive(); int maxYExclusive();
        boolean contains(int x, int y);
        default boolean containsImagePoint(double x, double y) { return contains((int)Math.floor(x), (int)Math.floor(y)); }
    }

    public record Detection(String id, String label, FrameIdentity frame, ImageMask mask) {}

    /** H maps original image pixel-edge coordinates into normalized depth texture UV.
     * For live capture the caller must freeze H from the same ARCore Frame and
     * independently check it against API points; a width ratio is not calibration.
     */
    public static final class Calibration {
        public final FrameIdentity frame;
        public final int imageWidth, imageHeight, depthWidth, depthHeight;
        public final String source;
        public final Intrinsics intrinsics;
        private final double[] h;
        public Calibration(FrameIdentity frame, int imageWidth, int imageHeight, int depthWidth,
                           int depthHeight, double[] imageToDepthUv, String source, Intrinsics intrinsics) {
            this.frame=frame; this.imageWidth=imageWidth; this.imageHeight=imageHeight;
            this.depthWidth=depthWidth; this.depthHeight=depthHeight; this.source=source; this.intrinsics=intrinsics;
            this.h=imageToDepthUv == null ? null : imageToDepthUv.clone();
        }
        public static Calibration affine(FrameIdentity frame, int w, int h, int dw, int dh,
                                          double[] affine, String source, Intrinsics intrinsics) {
            if (affine == null || affine.length != 6) throw new IllegalArgumentException("affine_requires_six_values");
            return new Calibration(frame,w,h,dw,dh,new double[]{affine[0],affine[1],affine[2],affine[3],affine[4],affine[5],0,0,1},source,intrinsics);
        }
        public double[] imageToDepthUv() { return h == null ? null : h.clone(); }
        private double[] inverse() {
            if (!Arrays.asList("synthetic_calibration","arcore_transform_coordinates2d","registered_rgbd_dataset").contains(source)) fail("uncalibrated_transform");
            if (imageWidth<=0 || imageHeight<=0 || depthWidth<=0 || depthHeight<=0 || (long)depthWidth*depthHeight>Integer.MAX_VALUE) fail("invalid_calibration_dimensions");
            if (h==null || h.length!=9 || !finite(h)) { fail("invalid_calibration_matrix"); }
            double a=h[0],b=h[1],c=h[2],d=h[3],e=h[4],f=h[5],g=h[6],i=h[7],j=h[8];
            double determinant=a*(e*j-f*i)-b*(d*j-f*g)+c*(d*i-e*g);
            if (!Double.isFinite(determinant) || Math.abs(determinant)<1e-15) fail("invalid_calibration_matrix");
            return new double[]{(e*j-f*i)/determinant,(c*i-b*j)/determinant,(b*f-c*e)/determinant,
                (f*g-d*j)/determinant,(a*j-c*g)/determinant,(c*d-a*f)/determinant,
                (d*i-e*g)/determinant,(b*g-a*i)/determinant,(a*e-b*d)/determinant};
        }
    }

    /** Copies input planes once, before the Android Images may be closed/reused.
     * Raw depth is axial z in unsigned uint16 millimeters; zero remains invalid.
     * Registered references have no sensor confidence or fabricated timestamps.
     */
    public static final class DepthFrame {
        public final FrameIdentity alignedFrame, confidenceFrame;
        public final long depthTimestampNs;
        public final String depthClockDomain, source;
        public final Calibration calibration;
        public final boolean reference;
        private final int[] mm;
        private final double[] meters;
        private final byte[] confidence;
        private DepthFrame(FrameIdentity frame,long timestamp,String clock,int[] mm,double[] meters,
                           byte[] confidence,Calibration calibration,FrameIdentity confidenceFrame,boolean reference,String source) {
            this.alignedFrame=frame;this.depthTimestampNs=timestamp;this.depthClockDomain=clock;
            this.mm=mm==null?null:mm.clone();this.meters=meters==null?null:meters.clone();
            this.confidence=confidence==null?null:confidence.clone();this.calibration=calibration;
            this.confidenceFrame=confidenceFrame;this.reference=reference;
            this.source=source;
        }
        public static DepthFrame raw(FrameIdentity frame,long timestamp,String clock,int[] mm,byte[] confidence,
                                     Calibration calibration,FrameIdentity confidenceFrame) {
            return new DepthFrame(frame,timestamp,clock,mm,null,confidence,calibration,confidenceFrame,false,"ARCORE_RAW_DEPTH");
        }
        /** Live smoothed depth has no confidence plane and is never a registered reference. */
        public static DepthFrame full(FrameIdentity frame,long timestamp,String clock,int[] mm,Calibration calibration) {
            return new DepthFrame(frame,timestamp,clock,mm,null,null,calibration,null,false,"ARCORE_FULL_DEPTH");
        }
        public static DepthFrame raw(FrameIdentity frame,long timestamp,String clock,short[] mm,byte[] confidence,
                                     Calibration calibration,FrameIdentity confidenceFrame) {
            int[] values=mm==null?null:new int[mm.length];
            if(mm!=null)for(int i=0;i<mm.length;i++)values[i]=mm[i]&65535;
            return raw(frame,timestamp,clock,values,confidence,calibration,confidenceFrame);
        }
        public static DepthFrame registered(FrameIdentity frame,double[] meters,Calibration calibration) {
            return new DepthFrame(frame,0,null,null,meters,null,calibration,null,true,"REGISTERED_RGBD_REFERENCE");
        }
        /** Already-quantized static reference, preserving the same mm/1000.0 values
         * without a temporary double plane and a second plane copy. Not live depth. */
        public static DepthFrame registeredMillimeters(FrameIdentity frame,int[] mm,Calibration calibration) {
            return new DepthFrame(frame,0,null,mm,null,null,calibration,null,true,"REGISTERED_RGBD_REFERENCE");
        }
        double z(int i) { return meters!=null?meters[i]:mm[i]/1000.0; }
    }

    /** Frozen v1 component sampler + frozen v2 aggregation policy. */
    public record SamplingPolicy(int minSamples,double minValidFraction,int minRawConfidence,
                                 double minDepthM,double maxDepthM,int erosionPixels,long maxDepthAgeNs,
                                 double dominantFraction,double clusterGapM,double clusterGapRelative,
                                 double outlierBandM,double maxIqrM,double componentAgreementM,
                                 double partialMinDominantMaskFraction) {
        public static SamplingPolicy defaults() { return new SamplingPolicy(12,.10,128,.2,15,1,250_000_000L,.65,.15,.08,.06,.30,.25,.80); }
    }

    /** Pixel indices use the frozen depth grid (y * depthWidth + x), not RGB.
     * This exact sparse geometry preserves holes and disconnected regions.
     */
    public static final class Component {
        public final int componentId,maskPixels,interiorPixels,validPixels,inlierPixels;
        public final Status status;
        public final String reason;
        public final Double axialDepthM,euclideanRangeM,axialP20M,axialP80M,axialMadM,rawConfidenceMedian;
        private final int[] depthPixelIndices;
        private int[] inlierDepthPixelIndices = new int[0];
        private double[] inlierImageCentroid;
        private Double axialIqrM;
        private int depthWidth;
        private Component(int id,int[] pixels,int interior,int valid,int inliers,Status status,String reason,
                          Double z,Double range,Double p20,Double p80,Double mad,Double confidence) {
            componentId=id;depthPixelIndices=pixels;maskPixels=pixels.length;interiorPixels=interior;
            validPixels=valid;inlierPixels=inliers;this.status=status;this.reason=reason;
            axialDepthM=z;euclideanRangeM=range;axialP20M=p20;axialP80M=p80;axialMadM=mad;rawConfidenceMedian=confidence;
        }
        public int[] depthPixelIndices() { return depthPixelIndices.clone(); }
        /** Actual robust support only; diagnostic geometry, never a new sampling rule. */
        public int[] inlierDepthPixelIndices() { return inlierDepthPixelIndices.clone(); }
        public double[] inlierImageCentroid() { return inlierImageCentroid == null ? null : inlierImageCentroid.clone(); }
        public Double axialIqrM() { return axialIqrM; }
        public int[] bboxDepthPx() {
            int left=Integer.MAX_VALUE,top=Integer.MAX_VALUE,right=0,bottom=0;
            for(int i:depthPixelIndices){int x=i%depthWidth,y=i/depthWidth;left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);}
            return new int[]{left,top,right,bottom};
        }
    }
    public record ScopedDistance(int componentId,double axialDepthM,Double euclideanRangeM) {}
    public record Support(int totalMaskPixels,double knownComponentMaskFraction,double unknownComponentMaskFraction,
                          double robustInlierMaskFraction,double dominantMaskFraction,double representedMaskFraction) {
        static Support empty() { return new Support(0,0,0,0,0,0); }
    }
    public static final class Result {
        public final String detectionId,label,frameId,reason,rangeReason,source;
        public final Status status;
        public final Scope distanceScope;
        public final Double axialDepthM,euclideanRangeM;
        public final Long depthTimestampNs;
        public final boolean newDepthInformation,componentDepthConflict;
        public final boolean riskEligible=false;
        public final ScopedDistance dominantComponentDistance;
        public final Support support;
        public final List<Component> components;
        public final List<Integer> closerComponentIds;
        /** Number of candidate depth pixels visited for this mask; never an RGB bitmap scan. */
        public final int mappedRoiPixels;
        private Result(Builder b) {
            detectionId=b.d.id;label=b.d.label;frameId=b.d.frame.frameId;reason=b.reason;rangeReason=b.rangeReason;source=b.source;
            status=b.status;distanceScope=b.scope;axialDepthM=b.z;euclideanRangeM=b.range;depthTimestampNs=b.timestamp;
            newDepthInformation=b.fresh;componentDepthConflict=b.conflict;dominantComponentDistance=b.dominant;
            support=b.support;components=List.copyOf(b.components);closerComponentIds=List.copyOf(b.closer);mappedRoiPixels=b.roiPixels;
        }
    }
    private static final class Builder {
        final Detection d;
        String reason="unavailable_depth",rangeReason="distance_unknown",source;
        Status status=Status.UNKNOWN;Scope scope=Scope.NONE;Double z,range;Long timestamp;boolean fresh,conflict;
        ScopedDistance dominant;Support support=Support.empty();List<Component> components=new ArrayList<>();
        List<Integer> closer=new ArrayList<>();int roiPixels;
        Builder(Detection d){this.d=d;}
        Result finish(){return new Result(this);}
    }

    private final SamplingPolicy p;
    private record LedgerKey(String session,String source) {}
    private final Map<LedgerKey,Long> lastTimestamp=new HashMap<>();
    private long distinctDepthUpdates;
    // Geometry only: current calibration/frame/intrinsics are never reused.
    private Geometry previousGeometry;
    // Single-threaded scratch for exact sorting of quantized millimeter values.
    // Cleared for every use; never retains samples or changes quality thresholds.
    private int[] millimeterHistogram=new int[0];
    // Private single-threaded workspaces. Every read is bounded by the current
    // ROI/component sample count; result arrays never alias these buffers.
    private int[] componentQueue=new int[0],validIndices=new int[0];
    private double[] sortedWorkspace=new double[0];
    public MaskDepthEstimator(){this(SamplingPolicy.defaults());}
    public MaskDepthEstimator(SamplingPolicy p){
        Objects.requireNonNull(p);
        if(p.minSamples<1 || !(p.minValidFraction>0 && p.minValidFraction<=1) || p.minRawConfidence<0 || p.minRawConfidence>255 ||
           !(p.dominantFraction>.5 && p.dominantFraction<=1) || !(p.minDepthM>0 && p.minDepthM<p.maxDepthM) ||
           p.erosionPixels<0 || p.maxDepthAgeNs<0 || !(p.partialMinDominantMaskFraction>0 && p.partialMinDominantMaskFraction<=1) ||
           !finite(p.minDepthM,p.maxDepthM,p.clusterGapM,p.clusterGapRelative,p.outlierBandM,p.maxIqrM,p.componentAgreementM) ||
           p.clusterGapM<0 || p.clusterGapRelative<0 || p.outlierBandM<0 || p.maxIqrM<0 || p.componentAgreementM<0) fail("invalid_sampling_policy");
        this.p=p;
    }
    public long distinctDepthUpdates(){return distinctDepthUpdates;}

    public List<Result> associate(List<Detection> detections,DepthFrame depth){
        List<Builder> builders=new ArrayList<>();for(Detection d:detections)builders.add(new Builder(d));
        if(depth==null)return finish(builders);
        Grid grid;
        try{
            validateDepth(depth);
            Geometry geometry=previousGeometry!=null&&previousGeometry.sameGeometry(depth.calibration)
                ?previousGeometry:new Geometry(depth.calibration);
            previousGeometry=geometry;grid=new Grid(depth.calibration,geometry);
        }catch(IllegalArgumentException e){for(Builder b:builders)b.reason=e.getMessage();return finish(builders);}
        LedgerKey key=new LedgerKey(depth.alignedFrame.sessionId,depth.source);Long previous=lastTimestamp.get(key);
        if(!depth.reference && previous!=null && depth.depthTimestampNs<previous){
            for(Builder b:builders)b.reason="out_of_order_depth_information";return finish(builders);
        }
        boolean fresh=!depth.reference&&(previous==null||depth.depthTimestampNs>previous),consumed=false;
        for(Builder b:builders){
            b.source=depth.source;
            if(!Objects.equals(b.d.frame,depth.alignedFrame)){b.reason="mask_depth_frame_mismatch";continue;}
            Raster raster;
            try{raster=rasterize(b.d.mask,grid);}catch(IllegalArgumentException e){b.reason=e.getMessage();continue;}
            consumed=true;b.timestamp=depth.reference?null:depth.depthTimestampNs;b.fresh=fresh;b.roiPixels=raster.bits.length;
            if(componentQueue.length<raster.bits.length)componentQueue=new int[raster.bits.length];
            List<int[]> components=raster.components(componentQueue);
            if(components.isEmpty()){b.reason="empty_mask_or_outside_depth_coverage";continue;}
            List<int[]> inlierGroups=new ArrayList<>(components.size());int total=0,knownArea=0,inlierCount=0,dominantIndex=0,dominantArea=-1;
            double minKnown=Double.POSITIVE_INFINITY,maxKnown=Double.NEGATIVE_INFINITY;
            for(int i=0;i<components.size();i++){
                int[] pixels=components.get(i);Sample sampled=sample(i,pixels,raster,depth,grid);
                Component c=sampled.component;c.depthWidth=depth.calibration.depthWidth;b.components.add(c);inlierGroups.add(sampled.inliers);
                c.inlierDepthPixelIndices=sampled.inliers;
                if(sampled.inliers.length>0){
                    double sx=0,sy=0;for(int pixel:sampled.inliers){sx+=grid.x[pixel];sy+=grid.y[pixel];}
                    c.inlierImageCentroid=new double[]{sx/sampled.inliers.length,sy/sampled.inliers.length};
                }
                total+=c.maskPixels;
                if(c.maskPixels>dominantArea){dominantIndex=i;dominantArea=c.maskPixels;}
                if(c.status==Status.KNOWN){knownArea+=c.maskPixels;inlierCount+=c.inlierPixels;minKnown=Math.min(minKnown,c.axialDepthM);maxKnown=Math.max(maxKnown,c.axialDepthM);}
            }
            Component dominant=b.components.get(dominantIndex);
            b.conflict=maxKnown-minKnown>p.componentAgreementM;
            if(dominant.status==Status.KNOWN)for(Component c:b.components)
                if(c.status==Status.KNOWN&&dominant.axialDepthM-c.axialDepthM>p.componentAgreementM)b.closer.add(c.componentId);
            double represented=0;
            if(knownArea==total&&!b.conflict){
                b.status=Status.KNOWN;b.scope=Scope.ALL_COMPONENTS;b.reason="supported_mask_interior";
                if(b.components.size()==1){ // Identical sample set; avoid sorting/calculating it again.
                    b.z=dominant.axialDepthM;b.range=dominant.euclideanRangeM;
                }else{
                    int[] indices=new int[inlierCount];int offset=0;
                    for(int[] part:inlierGroups){System.arraycopy(part,0,indices,offset,part.length);offset+=part.length;}
                    b.z=median(depths(depth,indices));b.range=medianRange(depth,grid,indices);
                }
                b.rangeReason=b.range==null?"missing_or_incompatible_intrinsics":"calibrated_rays";represented=1;
            }else if(dominant.status==Status.KNOWN && (double)dominantArea/total>=p.partialMinDominantMaskFraction){
                b.status=Status.PARTIAL;b.scope=Scope.DOMINANT_COMPONENT;
                b.reason=b.conflict?"dominant_component_supported_with_conflicting_regions":"dominant_component_supported_with_unresolved_regions";
                b.dominant=new ScopedDistance(dominant.componentId,dominant.axialDepthM,dominant.euclideanRangeM);
                b.rangeReason="whole_mask_distance_unknown";
                represented=(double)dominantArea/total;
            }else b.reason=knownArea<total?"one_or_more_components_unknown":"disconnected_components_disagree";
            b.support=new Support(total,(double)knownArea/total,(double)(total-knownArea)/total,(double)inlierCount/total,(double)dominantArea/total,represented);
        }
        if(consumed&&!depth.reference){lastTimestamp.put(key,depth.depthTimestampNs);if(fresh)distinctDepthUpdates++;}
        return finish(builders);
    }
    private static List<Result> finish(List<Builder> builders){List<Result> out=new ArrayList<>(builders.size());for(Builder b:builders)out.add(b.finish());return Collections.unmodifiableList(out);}
    private void validateDepth(DepthFrame d){
        FrameIdentity f=d.alignedFrame;Calibration c=d.calibration;
        if(f==null||c==null)fail("invalid_frame_identity_or_timestamp");
        if(d.reference){
            if(empty(f.sessionId)||empty(f.frameId)||f.cameraTimestampNs!=0||!"dataset_sample_no_clock".equals(f.clockDomain))fail("reference_must_use_static_sample_identity");
            if(!Objects.equals(c.frame,f))fail("calibration_frame_mismatch");
            if(!"registered_rgbd_dataset".equals(c.source))fail("reference_requires_explicit_registered_calibration");
            if(d.meters!=null){
                if(d.meters.length!=(long)c.depthWidth*c.depthHeight)fail("reference_depth_m_must_be_float_calibrated_shape");
            }else{
                if(d.mm==null||d.mm.length!=(long)c.depthWidth*c.depthHeight)fail("reference_depth_mm_must_be_uint16_calibrated_shape");
                for(int mm:d.mm)if(mm<0||mm>65535)fail("reference_depth_mm_must_be_uint16_calibrated_shape");
            }
            return;
        }
        if(empty(f.sessionId)||empty(f.frameId)||empty(f.clockDomain)||f.cameraTimestampNs<=0||d.depthTimestampNs<=0)fail("invalid_frame_identity_or_timestamp");
        if(!Objects.equals(d.depthClockDomain,f.clockDomain))fail("incompatible_clock_domains");
        if(d.depthTimestampNs>f.cameraTimestampNs)fail("depth_timestamp_in_future");
        if(f.cameraTimestampNs-d.depthTimestampNs>p.maxDepthAgeNs)fail("stale_depth_information");
        if(!Objects.equals(c.frame,f))fail("calibration_frame_mismatch");
        if(d.mm==null||d.mm.length!=(long)c.depthWidth*c.depthHeight)fail("depth_must_be_uint16_calibrated_shape");
        for(int mm:d.mm)if(mm<0||mm>65535)fail("depth_must_be_uint16_calibrated_shape");
        if("ARCORE_FULL_DEPTH".equals(d.source)) {
            if(d.depthTimestampNs!=f.cameraTimestampNs)fail("full_depth_not_current_camera_image");
            return;
        }
        if(!Objects.equals(d.confidenceFrame,f))fail("confidence_frame_mismatch");
        if(d.confidence==null)fail("raw_confidence_unavailable");
        if(d.confidence.length!=(long)c.depthWidth*c.depthHeight)fail("confidence_must_be_uint8_depth_shape");
    }

    /** One inverse coordinate grid per frame, reused by every detection. */
    private static final class Grid {
        final Calibration c;final double[] x,y;final boolean[] usable;
        Grid(Calibration current,Geometry geometry){c=current;x=geometry.x;y=geometry.y;usable=geometry.usable;}
    }
    /** Cache contains only numeric coordinates and the exact geometry key, never
     * frame identity, intrinsics, depth, confidence, masks, or sampled distances. */
    private static final class Geometry {
        final int imageWidth,imageHeight,depthWidth,depthHeight;final String source;final double[] h;
        final double[] x,y;final boolean[] usable;
        boolean sameGeometry(Calibration other){
            return imageWidth==other.imageWidth&&imageHeight==other.imageHeight&&
                depthWidth==other.depthWidth&&depthHeight==other.depthHeight&&
                Objects.equals(source,other.source)&&Arrays.equals(h,other.h);
        }
        Geometry(Calibration c){
            double[] a=c.inverse();imageWidth=c.imageWidth;imageHeight=c.imageHeight;depthWidth=c.depthWidth;depthHeight=c.depthHeight;source=c.source;h=c.h.clone();
            int n=c.depthWidth*c.depthHeight;x=new double[n];y=new double[n];usable=new boolean[n];
            for(int dy=0;dy<c.depthHeight;dy++)for(int dx=0;dx<c.depthWidth;dx++){
                int i=dy*c.depthWidth+dx;double u=(dx+.5)/c.depthWidth,v=(dy+.5)/c.depthHeight;
                double xx=a[0]*u+a[1]*v+a[2],yy=a[3]*u+a[4]*v+a[5],den=a[6]*u+a[7]*v+a[8];
                if(!Double.isFinite(xx)||!Double.isFinite(yy)||!Double.isFinite(den)||Math.abs(den)<=1e-12)continue;
                x[i]=xx/den;y[i]=yy/den;usable[i]=x[i]>=0&&x[i]<c.imageWidth&&y[i]>=0&&y[i]<c.imageHeight;
            }
        }
    }
    private static Raster rasterize(ImageMask m,Grid g){
        Calibration c=g.c;
        if(m==null||m.imageWidth()!=c.imageWidth||m.imageHeight()!=c.imageHeight)fail("mask_calibration_size_mismatch");
        int maskLeft=m.minX(),maskTop=m.minY(),maskRight=m.maxXExclusive(),maskBottom=m.maxYExclusive();
        if(maskLeft<0||maskTop<0||maskRight>c.imageWidth||maskBottom>c.imageHeight||maskLeft>maskRight||maskTop>maskBottom)fail("invalid_mask_roi");
        int left=0,top=0,right=c.depthWidth,bottom=c.depthHeight;
        double minU=Double.POSITIVE_INFINITY,maxU=Double.NEGATIVE_INFINITY,minV=minU,maxV=maxU,sign=0;
        boolean bounded=true;
        for(int y:new int[]{m.minY(),m.maxYExclusive()})for(int x:new int[]{m.minX(),m.maxXExclusive()}){
            double den=c.h[6]*x+c.h[7]*y+c.h[8],u=(c.h[0]*x+c.h[1]*y+c.h[2])/den,v=(c.h[3]*x+c.h[4]*y+c.h[5])/den;
            if(!finite(den,u,v)||Math.abs(den)<=1e-12||(sign!=0&&Math.signum(den)!=sign)){bounded=false;continue;}
            sign=Math.signum(den);minU=Math.min(minU,u);maxU=Math.max(maxU,u);minV=Math.min(minV,v);maxV=Math.max(maxV,v);
        }
        if(bounded){ // Extra pixel accounts for edge floating-point rounding; membership is still exact.
            left=clampFloor(minU*c.depthWidth-1,c.depthWidth);right=clampCeil(maxU*c.depthWidth+1,c.depthWidth);
            top=clampFloor(minV*c.depthHeight-1,c.depthHeight);bottom=clampCeil(maxV*c.depthHeight+1,c.depthHeight);
        }
        Raster r=new Raster(left,top,Math.max(0,right-left),Math.max(0,bottom-top),c.depthWidth);
        for(int y=top;y<bottom;y++)for(int x=left;x<right;x++){
            int i=y*c.depthWidth+x;
            if(g.usable[i]&&g.x[i]>=maskLeft&&g.x[i]<maskRight&&g.y[i]>=maskTop&&g.y[i]<maskBottom&&m.containsImagePoint(g.x[i],g.y[i]))r.bits[(y-top)*r.w+x-left]=1;
        }
        return r;
    }
    private static final class Raster {
        final int left,top,w,h,depthWidth;final byte[] bits;
        Raster(int left,int top,int w,int h,int depthWidth){this.left=left;this.top=top;this.w=w;this.h=h;this.depthWidth=depthWidth;bits=new byte[w*h];}
        boolean interior(int global,int radius){
            int x=global%depthWidth-left,y=global/depthWidth-top;
            if(x-radius<0||x+radius>=w||y-radius<0||y+radius>=h)return false;
            if(radius==1){
                int i=y*w+x;
                return bits[i-w-1]!=0&&bits[i-w]!=0&&bits[i-w+1]!=0&&bits[i-1]!=0&&bits[i]!=0&&bits[i+1]!=0&&bits[i+w-1]!=0&&bits[i+w]!=0&&bits[i+w+1]!=0;
            }
            for(int yy=y-radius;yy<=y+radius;yy++)for(int xx=x-radius;xx<=x+radius;xx++)if(bits[yy*w+xx]==0)return false;
            return true;
        }
        List<int[]> components(int[] queue){
            List<int[]> out=new ArrayList<>();
            for(int seed=0;seed<bits.length;seed++){
                if(bits[seed]!=1)continue;int head=0,tail=0;queue[tail++]=seed;bits[seed]=2;
                while(head<tail){
                    int i=queue[head++],x=i%w;
                    if(i>=w&&bits[i-w]==1){bits[i-w]=2;queue[tail++]=i-w;}
                    if(i<bits.length-w&&bits[i+w]==1){bits[i+w]=2;queue[tail++]=i+w;}
                    if(x>0&&bits[i-1]==1){bits[i-1]=2;queue[tail++]=i-1;}
                    if(x+1<w&&bits[i+1]==1){bits[i+1]=2;queue[tail++]=i+1;}
                }
                int[] pixels=new int[tail];for(int k=0;k<tail;k++)pixels[k]=(queue[k]/w+top)*depthWidth+queue[k]%w+left;
                out.add(pixels);
            }
            return out;
        }
    }
    private record Sample(Component component,int[] inliers) {}
    private Sample sample(int id,int[] pixels,Raster raster,DepthFrame d,Grid g){
        // At most one valid sample per component pixel: capacity is exact and
        // no dynamically growing collection or per-sample size field is needed.
        if(validIndices.length<pixels.length)validIndices=new int[pixels.length];
        int[] valid=validIndices;int validCount=0,n=0;
        for(int i:pixels)if(raster.interior(i,p.erosionPixels)){
            n++;double z=d.z(i);
            if(Double.isFinite(z)&&z>=p.minDepthM&&z<=p.maxDepthM&&(d.reference||"ARCORE_FULL_DEPTH".equals(d.source)||(d.confidence[i]&255)>=p.minRawConfidence))valid[validCount++]=i;
        }
        if(n<p.minSamples)return unknown(id,pixels,n,0,0,"insufficient_interior");
        if(validCount<p.minSamples||(double)validCount/n<p.minValidFraction)return unknown(id,pixels,n,validCount,0,"insufficient_valid_confident_depth");
        double[] sorted=sortedDepths(d,valid,validCount);int start=0,bestStart=0,bestEnd=0;
        for(int k=1;k<=validCount;k++){
            if(k==validCount||sorted[k]-sorted[k-1]>Math.max(p.clusterGapM,p.clusterGapRelative*(sorted[k]+sorted[k-1])/2)){
                if(k-start>bestEnd-bestStart){bestStart=start;bestEnd=k;}start=k;
            }
        }
        if((double)(bestEnd-bestStart)/validCount<p.dominantFraction)return unknown(id,pixels,n,validCount,0,"ambiguous_depth_layers");
        double med=quantileSorted(sorted,bestStart,bestEnd,.5);
        double mad=medianDeviationSorted(sorted,bestStart,bestEnd,med),band=Math.max(p.outlierBandM,3*1.4826*mad);
        int robustStart=bestStart,robustEnd=bestEnd;
        while(robustStart<robustEnd&&Math.abs(sorted[robustStart]-med)>band)robustStart++;
        while(robustEnd>robustStart&&Math.abs(sorted[robustEnd-1]-med)>band)robustEnd--;
        int count=robustEnd-robustStart;
        if(count<p.minSamples||(double)count/n<p.minValidFraction)return unknown(id,pixels,n,validCount,count,"insufficient_robust_support");
        if(quantileSorted(sorted,robustStart,robustEnd,.75)-quantileSorted(sorted,robustStart,robustEnd,.25)>p.maxIqrM)return unknown(id,pixels,n,validCount,count,"excessive_depth_spread");
        int[] kept=new int[count];int pos=0;double low=sorted[robustStart],high=sorted[robustEnd-1];
        for(int k=0;k<validCount;k++){int i=valid[k];double z=d.z(i);if(z>=low&&z<=high)kept[pos++]=i;}
        Double conf=null;if(d.confidence!=null){double[] values=new double[kept.length];for(int k=0;k<kept.length;k++)values[k]=d.confidence[kept[k]]&255;conf=median(values);}
        Component component=new Component(id,pixels,n,validCount,kept.length,Status.KNOWN,"dominant_interior_layer",
            quantileSorted(sorted,robustStart,robustEnd,.5),medianRange(d,g,kept),quantileSorted(sorted,robustStart,robustEnd,.2),quantileSorted(sorted,robustStart,robustEnd,.8),mad,conf);
        component.axialIqrM=quantileSorted(sorted,robustStart,robustEnd,.75)-quantileSorted(sorted,robustStart,robustEnd,.25);
        return new Sample(component,kept);
    }
    /** Produces the identical ascending multiset as Arrays.sort on mm/1000.0.
     * The branch chooses work strategy only; every sample and value is retained.
     * Arbitrary floating references and sparse/wide ranges retain comparison sort. */
    private double[] sortedDepths(DepthFrame d,int[] indices,int count){
        if(sortedWorkspace.length<count)sortedWorkspace=new double[count];
        double[] sorted=sortedWorkspace;
        if(d.mm!=null&&count>=128){
            int min=65535,max=0;
            for(int i=0;i<count;i++){int value=d.mm[indices[i]];min=Math.min(min,value);max=Math.max(max,value);}
            int span=max-min+1;
            if(span<=4L*count){
                if(millimeterHistogram.length<span)millimeterHistogram=new int[span];
                else Arrays.fill(millimeterHistogram,0,span,0);
                for(int i=0;i<count;i++)millimeterHistogram[d.mm[indices[i]]-min]++;
                int offset=0;
                for(int bin=0;bin<span;bin++){
                    int next=offset+millimeterHistogram[bin];
                    if(next>offset)Arrays.fill(sorted,offset,next,(min+bin)/1000.0);
                    offset=next;
                }
                return sorted;
            }
        }
        for(int i=0;i<count;i++)sorted[i]=d.z(indices[i]);
        Arrays.sort(sorted,0,count);return sorted;
    }
    private static Sample unknown(int id,int[] pixels,int interior,int valid,int inliers,String reason){
        return new Sample(new Component(id,pixels,interior,valid,inliers,Status.UNKNOWN,reason,null,null,null,null,null,null),new int[0]);
    }
    private static Double medianRange(DepthFrame d,Grid g,int[] indices){
        Intrinsics k=g.c.intrinsics;if(k==null||!k.validFor(g.c.imageWidth,g.c.imageHeight))return null;
        double[] ranges=new double[indices.length];for(int n=0;n<indices.length;n++){int i=indices[n];double x=(g.x[i]-k.cx)/k.fx,y=(g.y[i]-k.cy)/k.fy;ranges[n]=d.z(i)*Math.sqrt(1+x*x+y*y);}
        return median(ranges);
    }
    private static double[] depths(DepthFrame d,int[] indices){double[] z=new double[indices.length];for(int i=0;i<indices.length;i++)z[i]=d.z(indices[i]);return z;}
    private static double median(double[] values){Arrays.sort(values);return quantileSorted(values,.5);}
    private static double quantileSorted(double[] values,double q){return quantileSorted(values,0,values.length,q);}
    private static double quantileSorted(double[] values,int start,int end,double q){double position=q*(end-start-1);int low=(int)Math.floor(position),high=(int)Math.ceil(position);double fraction=position-low;return values[start+low]+(values[start+high]-values[start+low])*fraction;}
    /** Absolute deviations of a sorted layer are two monotone streams around
     * its median. Merge only through the middle order statistic; no new sort. */
    private static double medianDeviationSorted(double[] values,int start,int end,double median){
        int count=end-start,left=start+(count-1)/2,right=left+1;
        int low=(count-1)/2,high=count/2;double lower=0,upper=0;
        for(int rank=0;rank<=high;rank++){
            double a=left>=start?Math.abs(values[left]-median):Double.POSITIVE_INFINITY;
            double b=right<end?Math.abs(values[right]-median):Double.POSITIVE_INFINITY;
            double next;if(a<=b){next=a;left--;}else{next=b;right++;}
            if(rank==low)lower=next;if(rank==high)upper=next;
        }
        return lower+(upper-lower)*.5;
    }
    private static int clampFloor(double v,int max){return (int)Math.max(0,Math.min(max,Math.floor(v)));}
    private static int clampCeil(double v,int max){return (int)Math.max(0,Math.min(max,Math.ceil(v)));}
    private static boolean empty(String s){return s==null||s.isEmpty();}
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static void fail(String message){throw new IllegalArgumentException(message);}
}
