package kr.co.hanium.dreamup.walksafe.positioneval.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Demo5JniCancellationInstrumentedTest {
    @Test
    fun createCancelDestroyUsesStableJniNames() {
        val token = Demo5NativeBridge.createRunToken(AnalysisRunGeneration.next())
        try {
            token.cancel()
        } finally {
            token.close()
        }
    }
}
