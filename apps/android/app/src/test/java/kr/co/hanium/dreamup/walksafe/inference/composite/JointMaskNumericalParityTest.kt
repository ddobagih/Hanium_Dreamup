package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.unknown.*
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.*
import org.junit.Assert.*
import org.junit.Test
import sun.misc.Unsafe

class JointMaskNumericalParityTest {
    private fun raw(corner: Float = -.00001f): Array<FloatArray> {
        val d = FloatArray(FastSamModelContract.DETECTION_FLOATS)
        d[0]=.5f; d[12096]=.5f; d[2*12096]=1f; d[3*12096]=1f; d[4*12096]=.9f; d[5*12096]=1f
        val p = FloatArray(FastSamModelContract.PROTOTYPE_FLOATS)
        for(y in 0 until 192) for(x in 0 until 192) p[(y*192+x)*32]=if(x in 20..170 && y in 20..170) 1f else -1f
        p[(20*192+20)*32]=corner
        return arrayOf(d,p)
    }
    private fun checkPair(a: Array<FloatArray>, b: Array<FloatArray>, mutate: (MutableList<InstanceMask>)->Unit = {}): Pair<Pair<Boolean,Boolean>,List<String>> {
        val decoder=FastSamDecoder(); val am=decoder.decode(a[0],a[1],192,192).instances()
        val bm=decoder.decode(b[0],b[1],192,192).instances().toMutableList(); mutate(bm)
        val fixture=JointRuntimeSampleTestFixture.sampleFixtures().use { bundle -> bundle.borrow().use { it.values.first() } }
        val refs=am.map { JointReferenceMask(it.anchorIndex(),192,192,it.maskLeft(),it.maskTop(),it.maskRightExclusive(),it.maskBottomExclusive(),it.copyPackedBits()) }
        val frame=JointReferenceFrame(fixture.definition,fixture.argbDigest,"unused",floatArrayOf(0f),emptyList(),a,refs)
        val owner=JointRuntimeReferenceSet.fromOwnedForTest(JointRuntimeTestFixtures.binding,requireNotNull(JointRuntimeTestFixtures.config.unifiedWalksafe),mapOf(fixture.definition.id to frame))
        val unsafe=Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }.get(null) as Unsafe
        val sample=unsafe.allocateInstance(FastSamResult::class.java) as FastSamResult<*>
        FastSamResult::class.java.getField("masks").apply { isAccessible=true }.set(sample,bm)
        val raw=FastSamResult.RawOutputs::class.java.declaredConstructors.single().apply { isAccessible=true }.newInstance(b as Any)
        FastSamResult::class.java.getField("rawOutputs").apply { isAccessible=true }.set(sample,raw)
        val lines=mutableListOf<String>()
        return owner.claimForFinal(JointRuntimeTestFixtures.binding) { lines+=it }.use { it.auxiliary(fixture,sample) } to lines
    }
    @Test fun finiteRawToleranceAndNearZeroAreBothMandatory() {
        val a=raw(); val b=raw(.00001f); b[0][6*12096]=1f
        assertFalse(checkPair(a,b).first.first)
        assertFalse(checkPair(a,b).first.second)
        val nonfinite=raw(.00001f); nonfinite[0][6*12096]=Float.NaN
        val ctor=FastSamResult.RawOutputs::class.java.declaredConstructors.single().apply { isAccessible=true }
        assertFalse(JointRuntimeReferenceSet.equivalentRaw(a,ctor.newInstance(nonfinite as Any) as FastSamResult.RawOutputs))
        // Cancellation-amplified output still passes raw tolerance but exceeds the logit band.
        val c=raw(); val d=raw()
        c[0][5*12096]=100f; d[0][5*12096]=100f
        c[0][6*12096]=100f; d[0][6*12096]=100f
        c[1][(20*192+20)*32]=1f; d[1][(20*192+20)*32]=1f
        c[1][(20*192+20)*32+1]=-1.00002f; d[1][(20*192+20)*32+1]=-.99998f
        val band=checkPair(c,d).first; assertTrue(band.first); assertFalse(band.second)
    }
    @Test fun interiorHoleIsRejectedEvenWhenOnlyOneNearZeroPixelDiffers() {
        val a=raw(); val b=raw(); a[1][(100*192+100)*32]=-.00001f; b[1][(100*192+100)*32]=.00001f
        assertTrue(checkPair(a,b).first.first); assertFalse(checkPair(a,b).first.second)
    }
    @Test fun missingAddedAndDuplicateObjectsAreRejected() {
        assertFalse(checkPair(raw(),raw()) { it.clear() }.first.second)
        assertFalse(checkPair(raw(),raw()) { it+=it.first() }.first.second)
        val a=raw(); val b=raw(); a[0][4*12096]=.24999f; b[0][4*12096]=.25001f
        val result=checkPair(a,b).first; assertTrue(result.first); assertFalse(result.second)
    }
    @Test fun tinyMasksAndThinEndpointsAreNotAllowedToDisappear() {
        val a=raw(); val b=raw(.00001f)
        for(y in 0 until 192) for(x in 0 until 192) if(x !in 20..22 || y !in 20..22) { a[1][(y*192+x)*32]=-1f; b[1][(y*192+x)*32]=-1f }
        assertFalse(checkPair(a,b).first.second)
        val c=raw(); val d=raw()
        for(x in 171..180) { c[1][(100*192+x)*32]=1f; d[1][(100*192+x)*32]=1f }
        c[1][(100*192+180)*32]=-.00001f; d[1][(100*192+180)*32]=.00001f
        assertFalse(checkPair(c,d).first.second)
    }
    @Test fun adjacentEditsLargeErrorsAndUnexplainedPackedBitsAreRejected() {
        val a=raw(); val b=raw(.00001f)
        a[1][(20*192+21)*32]=-.00001f; b[1][(20*192+21)*32]=.00001f
        assertFalse(checkPair(a,b).first.second)
        val large=raw(); for(x in 20..170) large[1][(20*192+x)*32]=-1f
        assertFalse(checkPair(raw(),large).first.second)
        val c=raw(); val d=raw(.00001f)
        assertFalse(checkPair(c,d) { masks ->
            val m=masks.first(); val bits=java.util.BitSet.valueOf(m.copyPackedBits()); bits.flip(30*192+30)
            masks[0]=InstanceMask::class.java.declaredConstructors.single().apply { isAccessible=true }.newInstance(192,192,0,0,192,192,bits.cardinality(),0,0f,0f,192f,192f,.9f,bits) as InstanceMask
        }.first.second)
    }
    @Test fun exactEmptyUnionIsAcceptedAndPaddingCorruptionIsRejected() {
        val a=raw(); a[0][4*12096]=0f
        assertEquals(true to true,checkPair(a,a).first)
        val raw=FastSamResult.RawOutputs::class.java.declaredConstructors.single().apply { isAccessible=true }.newInstance(a as Any) as FastSamResult.RawOutputs
        val bits=java.util.BitSet().apply { set(0); set(7) }
        val m=InstanceMask::class.java.declaredConstructors.single().apply { isAccessible=true }.newInstance(3,1,0,0,3,1,1,0,0f,0f,3f,1f,.9f,bits) as InstanceMask
        val lines=mutableListOf<String>()
        assertFalse(JointMaskNumericalParity.compare(listOf(JointReferenceMask(0,3,1,0,0,3,1,byteArrayOf(1))),listOf(m),a,raw,true) { lines+=it })
        assertTrue(lines.single().contains("padding_set_bits=1"))
    }
    @Test fun sparseReplayMatchesRealDecoderAcrossLetterboxAndInterpolationSizes() {
        val a=raw(); val d=java.nio.FloatBuffer.wrap(a[0]); val p=java.nio.FloatBuffer.wrap(a[1])
        for((w,h) in listOf(192 to 192,193 to 127,640 to 480,127 to 193)) {
            val m=FastSamDecoder().decode(a[0],a[1],w,h).instances().single()
            for(y in 0 until h step maxOf(1,h/11)) for(x in 0 until w step maxOf(1,w/11))
                assertEquals(m.contains(x,y),FastSamDecoder.maskLogitAt(d,p,0,w,h,x,y)>0f)
        }
    }
    @Test fun eightIndependentBoundaryCrossingsPassButNineExceedAbsoluteCap() {
        for(count in listOf(8,9)) {
            val a=raw(); val b=raw()
            for(k in 0 until count) { a[1][((30+k*10)*192+20)*32]=-.00001f; b[1][((30+k*10)*192+20)*32]=.00001f }
            val result=checkPair(a,b)
            assertTrue(result.first.first); assertEquals(count==8,result.first.second)
            result.second.filter { "event=JOINT_MASK_PARITY" in it }.forEach(::println)
        }
    }
    @Test fun genuineDecoderThresholdCrossingWithEquivalentFiniteRawIsAcceptedByActualVerifier() {
        val (verdict,lines)=checkPair(raw(),raw(.00001f))
        assertTrue("raw remains numerically equivalent",verdict.first)
        assertTrue("small simple boundary threshold crossing must pass",verdict.second)
        assertTrue(lines.any { "event=JOINT_MASK_PARITY" in it && "valid_xor=1" in it && "accepted=true" in it })
    }
}
