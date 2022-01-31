package com.google.firebase.firestore.benchmark

import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.google.firebase.firestore.testutil.IntegrationTestUtil
import org.junit.After

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*
import java.util.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class QueryBenchmark {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.google.firebase.firestore.benchmark", appContext.packageName)
    }

    @After
    fun tearDown() {
        IntegrationTestUtil.tearDown()
    }

    @Test
    fun indexOverlays() {
        val documentReference= IntegrationTestUtil.testFirestore()
    }
}