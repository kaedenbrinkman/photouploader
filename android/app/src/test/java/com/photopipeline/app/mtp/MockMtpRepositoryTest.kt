package com.photopipeline.app.mtp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MockMtpRepositoryTest {
    @Test
    fun importProducesSha256() {
        runBlocking {
        val repo = MockMtpRepository()
        repo.refreshConnection()
        val obj = repo.listCr3Objects().first()
        val dest = File.createTempFile("mtp", ".CR3")
        val sha = repo.importObject(obj, dest.absolutePath) {}
        assertTrue(sha.length == 64)
        dest.delete()
        }
    }
}
