package kr.co.hanium.dreamup.walksafe.voice

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanOfflineTtsVoiceTest {
    @Test
    fun twoAndThreeLetterKoreanCodesIncludeTheObservedSamsungDefaultLocale() {
        listOf(
            Locale.KOREAN,
            Locale.KOREA,
            Locale.forLanguageTag("ko-KR"),
            Locale.forLanguageTag("kor-KR"),
            Locale.forLanguageTag("kor-default"),
            Locale("kor", "", "default"),
            Locale("KOR"),
        ).forEach { locale ->
            assertTrue(locale.toLanguageTag(), isKoreanLocale(locale))
            assertTrue(isInstalledOfflineKoreanVoice(locale, networkRequired = false, notInstalled = false))
        }
    }

    @Test
    fun koreanLookingNamesOrUnrelatedLocaleCodesDoNotMatch() {
        listOf(Locale.ROOT, Locale.ENGLISH, Locale.JAPANESE, Locale("korea"), Locale("kok"), Locale("korx"))
            .forEach { locale -> assertFalse(locale.toLanguageTag(), isKoreanLocale(locale)) }
    }

    @Test
    fun neitherKoreanCodeCanBypassNetworkOrNotInstalledRestrictions() {
        for (locale in listOf(Locale.KOREAN, Locale("kor"))) {
            assertFalse(isInstalledOfflineKoreanVoice(locale, networkRequired = true, notInstalled = false))
            assertFalse(isInstalledOfflineKoreanVoice(locale, networkRequired = false, notInstalled = true))
            assertFalse(isInstalledOfflineKoreanVoice(locale, networkRequired = true, notInstalled = true))
        }
        assertFalse(isInstalledOfflineKoreanVoice(Locale.ENGLISH, networkRequired = false, notInstalled = false))
    }
}
