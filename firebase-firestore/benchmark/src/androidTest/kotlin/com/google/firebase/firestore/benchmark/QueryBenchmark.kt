package com.google.firebase.firestore.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.common.util.CollectionUtils.mapOf
import com.google.firebase.firestore.AccessHelper
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.testutil.IntegrationTestUtil
import com.google.firebase.firestore.testutil.IntegrationTestUtil.waitFor
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class QueryBenchmark() {

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    lateinit var firestore:FirebaseFirestore ;
    lateinit var collection: CollectionReference;

    @After
    fun tearDown() {
        IntegrationTestUtil.tearDown()
    }

    @Before
    fun before() {
        firestore = IntegrationTestUtil.testFirestore()
        collection = firestore.collection(UUID.randomUUID().toString())

        firestore.disableNetwork()

        for (i in 1..50) {
            collection.add(mapOf("count" to i))
        }
    }

    @Test
    fun overlaysWithoutIndex() {
        val query = collection.whereLessThan("count", 10)
        benchmarkRule.measureRepeated {
            waitFor(query.get())
        }
    }

    @Test
    fun overlaysWithIndex() {
        val query = collection.whereLessThan("count", 10)

        AccessHelper.setIndexConfiguration(firestore, "{\n" +
                "  indexes: [\n" +
                "    { \n" +
                "      collectionGroup: \"" + collection.id + "\",\n" +
                "      fields: [\n" +
                "        { fieldPath: \"count\", order: \"ASCENDING\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  ]}\n")

        waitFor(AccessHelper.forceBackfill(firestore))

        benchmarkRule.measureRepeated {
            waitFor(query.get())
        }
    }
}
