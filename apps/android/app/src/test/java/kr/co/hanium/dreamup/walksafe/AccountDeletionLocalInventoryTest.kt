package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AccountDeletionLocalInventoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun eachStoreFailureKeepsEvidenceFailedWhileOtherStoresAreStillPurgedAndRetryCompletes() {
        for (failing in AccountDeletionLocalStore.entries) {
            for (throws in listOf(false, true)) {
                val root = temporary.newFolder()
                val files = AccountDeletionLocalStore.entries.associateWith {
                    java.io.File(root, it.name).apply { writeText("synthetic account marker") }
                }
                val memory = AccountDeletionLocalStore.entries.toMutableSet()
                var injectFailure = true
                val actions = files.mapValues { (store, file) -> {
                    if (store == failing && injectFailure) {
                        if (throws) error("synthetic storage failure")
                        false
                    } else {
                        memory.remove(store)
                        (!file.exists() || file.delete()) && !file.exists()
                    }
                } }
                val inventory = AccountDeletionLocalInventory(actions)
                assertEquals(setOf(failing), inventory.purge())
                assertEquals(setOf(failing), memory)
                assertEquals(listOf(failing.name), root.list()!!.toList())
                injectFailure = false
                assertTrue(inventory.purge().isEmpty())
                assertTrue(memory.isEmpty())
                assertTrue(root.list()!!.isEmpty())
                assertTrue(inventory.purge().isEmpty())
            }
        }
    }

    @Test fun omittedStoreNeverBecomesDeleted() {
        for (missing in AccountDeletionLocalStore.entries) {
            val actions = AccountDeletionLocalStore.entries.filter { it != missing }.associateWith { { true } }
            assertEquals(setOf(missing), AccountDeletionLocalInventory(actions).purge())
        }
    }
}
