package com.google.firebase.firestore.benchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
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

    // do one shared test setup that sets up colelctiosn for RDC and overlays that can be re-used
    // make test paramterized

    val NUMBER_OF_RESULTS = 10
    val NUMBER_OF_PROPERTIES = 100
    val NUMBER_OF_DOCUMENTS = 250

    @get:Rule
    val benchmarkRule = BenchmarkRule()

    lateinit var firestore: FirebaseFirestore 
    lateinit var collection: CollectionReference

    @After
    fun tearDown() {
        IntegrationTestUtil.tearDown()
    }

    @Before
    fun before() {
        firestore = IntegrationTestUtil.testFirestore()
        collection = firestore.collection(UUID.randomUUID().toString())

        firestore.disableNetwork()

        val data = mutableMapOf<String, Int>()
        for (i in 1..NUMBER_OF_PROPERTIES) {
            data.put(Integer.toString(i), i)
        }

        val batch = firestore.batch()
        for (i in 1..NUMBER_OF_DOCUMENTS) {
            data.put("count", i)
            batch.set(collection.document(), data)
        }
        batch.commit()
    }

    @Test
    fun overlaysWithoutIndex() {
        val query = collection.whereLessThanOrEqualTo("count", NUMBER_OF_RESULTS)
            benchmarkRule.measureRepeated {

                waitFor(query.get())
        }
    }

    @Test
    fun overlaysWithIndex() {
        val query = collection.whereLessThanOrEqualTo("count", NUMBER_OF_RESULTS)

        AccessHelper.setIndexConfiguration(firestore, "{\n" +
                "  indexes: [\n" +
                "    { \n" +
                "      collectionGroup: \"" + collection.id + "\",\n" +
                "      fields: [\n" +
                "        { fieldPath: \"count\", order: \"ASCENDING\"}\n" +
                "      ]\n" +
                "    }\n" +
                "  ]}\n")
        // TODO(mrschmidt): Figure out why this has a document id
        // 2022-02-04 17:36:45.061 17648-17696/? I/Firestore: (24.0.1) [SQLiteIndexManager]: Using index 'FieldIndex{indexId=0, collectionGroup=d34a678b-67ec-4737-883c-21c354c5064b, segments=[Segment{fieldPath=count, kind=ASCENDING}], indexState=IndexState{sequenceNumber=2, offset=IndexOffset{readTime=SnapshotVersion(seconds=0, nanos=0), documentKey=d34a678b-67ec-4737-883c-21c354c5064b/zxqJIhFLbG2eBdVwslS1, largestBatchId=50}}}' to execute 'Query(d34a678b-67ec-4737-883c-21c354c5064b where count < 10 order by count, __name__)' (Arrays: null, Lower bound: Bound(inclusive=true, position=NaN), Upper bound: Bound(inclusive=false, position=10))

        waitFor(AccessHelper.forceBackfill(firestore))
        benchmarkRule.measureRepeated {
            waitFor(query.get())
        }
    }

//    @Test
//    fun remoteDocumentsWithoutIndex() {
//        val query = collection.whereLessThanOrEqualTo("count", NUMBER_OF_RESULTS)
//        benchmarkRule.measureRepeated {
//            waitFor(query.get())
//        }
//    }
//
//    @Test
//    fun remoteDocumentsWithIndex() {
//        val query = collection.whereLessThanOrEqualTo("count", NUMBER_OF_RESULTS)
//        benchmarkRule.measureRepeated {
//
//            waitFor(query.get())
//        }
//    }
//
//    @Test
//    fun remoteDocumentsWithFree() {
//        val query = collection.whereLessThanOrEqualTo("count", NUMBER_OF_RESULTS)
//        benchmarkRule.measureRepeated {
//
//            waitFor(query.get())
//        }
//    }
}
