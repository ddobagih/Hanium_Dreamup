package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.nio.file.Files
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.ReportStaticSourceInspector
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import org.junit.Assert.*
import org.junit.Test

class MainActivitySpeechStorageCoordinatorIntegrationTest {
    @Test fun actualPureFactoryReusesOriginalConsentPermissionDeletionAndActorQueryOwners() {
        val main=MainActivity();val owner=owner(main)
        assertNull(owner.collectionOrNull());assertNull(owner.authorityOrNull())
        assertSame(field(main,"integratedConsentSession"),(field(owner,"consentSession") as Function0<*>).invoke())
        assertSame(field(main,"permissionSessionPolicy"),(field(owner,"permissionPolicy") as Function0<*>).invoke())
        assertSame(call(main,"getAccountDeletionStateMachine",AccountDeletionStateMachine::class.java),(field(owner,"deletionStateMachine") as Function0<*>).invoke())
        val query=field(owner,"queries") as SpeechStorageQueries
        assertFalse(query.lifecycleInitialized());assertNull(query.currentActuator())
        assertFalse((field(main,"reportCleanupCallbackHandler\$delegate") as Lazy<*>).isInitialized())
    }
    @Test fun oneAtomicStateOwnerReplacesAllMainBackingFieldsAndKeepsPermissionSetterFencing() {
        val main=MainActivity();val owner=owner(main)
        val names=MainActivity::class.java.declaredFields.map { it.name }
        for(name in listOf("speechOutputCollection","speechOutputCollectionCreationLock","speechOutputAccountPurgeVerified",
            "speechOutputResetParticipant","speechOutputStorageAuthority","speechOutputAdmissionToken",
            "speechOutputAccountDeletionIntent","speechOutputAccountDeletionToken")) {
            assertFalse(name,name in names);assertNotNull(owner.javaClass.getDeclaredField(name))
        }
        val token=field(owner,"speechOutputAdmissionToken") as java.util.concurrent.atomic.AtomicReference<*>
        val before=token.get()
        invoke(main,"setPermissionSessionPolicy",Void.TYPE,arrayOf(PermissionSessionPolicy::class.java),PermissionSessionPolicy())
        assertNotSame(before,token.get())
    }
    @Test fun realMainCollectionDelegateCreatesTheSameSingleWindowAndCurrentnessLinksBackToThisOwner() {
        val main=MainActivity();val root=Files.createTempDirectory("main-speech-storage-coordinator-").toFile()
        var collection:SpeechOutputCollection?=null
        try {
            collection=invoke(main,"speechOutputCollectionOnWorker",SpeechOutputCollection::class.java,
                arrayOf(File::class.java,Boolean::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!),root,false,false) as SpeechOutputCollection
            assertSame(collection,owner(main).collectionOrNull())
            val again=invoke(main,"speechOutputCollectionOnWorker",SpeechOutputCollection::class.java,
                arrayOf(File::class.java,Boolean::class.javaPrimitiveType!!,Boolean::class.javaPrimitiveType!!),root,false,false)
            assertSame(collection,again);assertFalse(File(root,"field_sessions").exists())
            call(main,"retireSpeechOutputCollection",Void.TYPE)
            assertTrue(collection.retired.get());assertNull(owner(main).authorityOrNull())
        } finally {
            collection?.store?.closeAndDrain()?.get()
            check(root.deleteRecursively())
        }
    }
    @Test fun actualMainDelegatesBindTheSameOwnerAndRetainCapturedReceiptAndListenerInDomain() {
        val source=source("walksafe.speech.storage.main.source",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
        assertTrue(source.contains("override fun invalidate() = invalidateSpeechOutputStorageBinding()"))
        assertTrue(source.contains("override fun refresh(actuator: AndroidFeedbackActuator) = refreshSpeechOutputStorageBinding(actuator)"))
        for((entry,method) in listOf("refreshSpeechOutputStorageBinding" to "refreshBinding","recoverSpeechOutputCollectionOnWorker" to "recoverOnWorker",
            "speechOutputStorageBindingIsCurrent" to "bindingIsCurrent","prepareSpeechOutputAccountPurgeOnWorker" to "prepareAccountPurgeOnWorker",
            "awaitPreparedSpeechOutputAccountPurge" to "awaitPreparedAccountPurge","publishSpeechOutputCollectionAfterVerifiedAccountReset" to "publishAfterVerifiedAccountReset")) {
            val helper = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
            val body = if (entry == "publishSpeechOutputCollectionAfterVerifiedAccountReset") {
                val reset = helper.composition(source, "accountDeletionInstallationResetController")
                assertTrue(reset.contains("override fun prepareFreshSpeechCollection(): PreparedResetSpeechCollection? = captureFreshInstallationResetSpeechCollection()"))
                assertTrue(helper.member(source, "captureFreshInstallationResetSpeechCollection").contains("privacyAuthority.captureFreshInstallationResetSpeechCollection()"))
                val graph = this.source("walksafe.privacy.authority.source",
                    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/privacy/PrivacyAuthorityComposition.kt")
                val capture = helper.member(graph, "captureFreshInstallationResetSpeechCollection")
                assertTrue(capture.contains("speechOutputStorageCoordinator.prepareAfterVerifiedAccountReset() ?: return null"))
                assertTrue(capture.contains("override fun publish(): Boolean = speechOutputStorageCoordinator.publishAfterVerifiedAccountReset(replacement)"))
                capture
            } else helper.member(source, entry)
            assertTrue(entry,body.contains("speechOutputStorageCoordinator.$method("))
        }
        val owner=source("walksafe.speech.storage.owner.source",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/SpeechOutputStorageCoordinator.kt")
        assertTrue(owner.contains("previousCollection.recoveryReceipt.get() === receipt"))
        assertTrue(owner.contains("speechOutputCollection.compareAndSet(previousCollection, prepared)"))
        assertTrue(owner.contains("actuator.speechOutputListener = { event -> collection.store.enqueue(binding, event) }"))
        assertTrue(owner.contains("feedbackGeneration() != capture.feedbackGeneration"))
        assertTrue(owner.contains("accountDeletionIntentToken()"))
        assertFalse(owner.contains("MainActivity"))
    }
    private fun source(property: String, path: String): String =
        System.getProperty(property)?.let { File(it).readText() } ?: ReportStaticSourceInspector.read(path)
    private fun owner(main:MainActivity)=field(main,"speechOutputStorageCoordinator") as SpeechOutputStorageCoordinator
    private fun field(receiver:Any,name:String)=receiver.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(receiver)
    private fun call(main:MainActivity,name:String,result:Class<*>)=invoke(main,name,result,emptyArray())
    private fun invoke(main:MainActivity,name:String,result:Class<*>,types:Array<Class<*>>,vararg values:Any?):Any?=
        MethodHandles.privateLookupIn(MainActivity::class.java,MethodHandles.lookup()).findVirtual(MainActivity::class.java,name,
            MethodType.methodType(result,types.toList())).bindTo(main).invokeWithArguments(values.toList())
}
