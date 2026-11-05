package kr.co.hanium.dreamup.walksafe.inference.composite

import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamResult
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.FastSamDecoder
import kr.co.hanium.dreamup.walksafe.inference.unknown.segmentation.InstanceMask
import java.nio.FloatBuffer
import kotlin.math.abs

/** Strict structure plus sparse, topology-preserving near-zero threshold equivalence.
 * This is NOT general segmentation quality or a waiver of the independent raw gate. */
internal object JointMaskNumericalParity {
    internal fun compare(reference: List<JointReferenceMask>, actual: List<InstanceMask>,
        expectedRaw: Array<FloatArray>, raw: FastSamResult.RawOutputs, rawMatch: Boolean,
        diagnostic: (String) -> Unit): Boolean {
        if(reference.size > 100 || reference.size != actual.size ||
            reference.map { it.anchor }.distinct().size != reference.size ||
            actual.map { it.anchorIndex() }.distinct().size != actual.size) return false
        var all = true
        for((a,b) in reference.sortedBy { it.anchor }.zip(actual.sortedBy { it.anchorIndex() })) {
            if(a.anchor != b.anchorIndex() || a.width !in 1..1024 || a.height !in 1..1024 ||
                a.width != b.imageWidth() || a.height != b.imageHeight() ||
                a.left < 0 || a.top < 0 || a.right !in a.left+1..a.width || a.bottom !in a.top+1..a.height ||
                a.left != b.maskLeft() || a.top != b.maskTop() || a.right != b.maskRightExclusive() || a.bottom != b.maskBottomExclusive()) return false
            val w=a.right-a.left; val h=a.bottom-a.top; val n=w*h; val bb=b.copyPackedBits()
            if(a.bits.size != (n+7)/8 || bb.size != a.bits.size) return false
            fun bit(bits: ByteArray, i: Int) = (bits[i/8].toInt() and (1 shl (i%8))) != 0
            fun at(bits: ByteArray, x: Int, y: Int) = x in 0 until w && y in 0 until h && bit(bits,y*w+x)
            var areaA=0; var areaB=0; var intersection=0; var xor=0; var padding=0
            val changed=ArrayList<Int>(8)
            for(i in 0 until n) {
                val av=bit(a.bits,i); val bv=bit(bb,i)
                if(av) areaA++; if(bv) areaB++; if(av && bv) intersection++
                if(av != bv) { xor++; if(changed.size < 9) changed+=i }
            }
            for(i in n until a.bits.size*8) { if(bit(a.bits,i)) padding++; if(bit(bb,i)) padding++ }
            val union=areaA+areaB-intersection
            val iou=if(union==0) 1.0 else intersection.toDouble()/union
            val limit=minOf(8,minOf(areaA,areaB)/1000)
            var accepted=padding==0 && areaB==b.area() && (xor==0 ||
                rawMatch && xor<=limit && union>0 && iou>=.999)
            var boundary=0; var verified=0; var maxLogit=0.0
            if(xor>0 && xor<=8) for(i in changed) {
                val x=i%w; val y=i/w
                // Independent simple points only: no nearby edits can jointly cut a structure.
                val isolated=changed.none { j -> j!=i && abs(j%w-x)<=1 && abs(j/w-y)<=1 }
                val ring=BooleanArray(9) { k -> at(a.bits,x+k%3-1,y+k/3-1) }
                val cardinal=listOf(1,3,5,7).count { ring[it] }
                val simple=isolated && cardinal in 2..3 && components(ring,true,true)==1 && components(ring,false,false)==1
                if(simple) boundary++
                if(accepted && simple && expectedRaw.size==2) {
                    val av=FastSamDecoder.maskLogitAt(FloatBuffer.wrap(expectedRaw[0]),FloatBuffer.wrap(expectedRaw[1]),a.anchor,a.width,a.height,x+a.left,y+a.top)
                    val bv=FastSamDecoder.maskLogitAt(raw.detections(),raw.prototypes(),a.anchor,a.width,a.height,x+a.left,y+a.top)
                    if(av.isFinite() && bv.isFinite()) {
                        maxLogit=maxOf(maxLogit,abs(av.toDouble()),abs(bv.toDouble()))
                        if((av>0)==bit(a.bits,i) && (bv>0)==bit(bb,i) && abs(av)<=.001f && abs(bv)<=.001f) verified++
                    }
                }
            }
            if(xor>0) accepted=accepted && boundary==xor && verified==xor
            all=all && accepted
            JointRuntimeFinalDiagnostic.emit(diagnostic,"event=JOINT_MASK_PARITY area_ref=$areaA area_actual=$areaB intersection=$intersection union=$union iou=$iou valid_xor=$xor padding_set_bits=$padding xor_limit=$limit boundary_verified=$boundary raw_sign_verified=$verified max_abs_logit=$maxLogit raw_match=$rawMatch accepted=$accepted")
        }
        return all
    }

    /** Local digital topology: foreground 8-connected, background 4-connected, center excluded. */
    private fun components(ring: BooleanArray, foreground: Boolean, diagonal: Boolean): Int {
        val seen=BooleanArray(9); var count=0
        for(start in 0..8) if(start!=4 && !seen[start] && ring[start]==foreground) {
            count++; val queue=java.util.ArrayDeque<Int>(); queue.add(start); seen[start]=true
            while(queue.isNotEmpty()) {
                val i=queue.removeFirst()
                for(j in 0..8) if(j!=4 && !seen[j] && ring[j]==foreground) {
                    val dx=abs(i%3-j%3); val dy=abs(i/3-j/3)
                    if(if(diagonal) maxOf(dx,dy)==1 else dx+dy==1) { seen[j]=true; queue.add(j) }
                }
            }
        }
        return count
    }
}
