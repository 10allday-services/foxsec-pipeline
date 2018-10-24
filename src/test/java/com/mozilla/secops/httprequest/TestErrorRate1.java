package com.mozilla.secops.httprequest;

import org.junit.Test;
import org.junit.Rule;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.values.KV;

import org.joda.time.Instant;

import com.mozilla.secops.parser.Event;

import java.io.IOException;
import java.util.Scanner;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

public class TestErrorRate1 {
    public TestErrorRate1() {
    }

    private PCollection<String> getInput() throws IOException {
        ArrayList<String> inputData = new ArrayList<String>();
        GZIPInputStream in = new GZIPInputStream(getClass()
                .getResourceAsStream("/testdata/httpreq_errorrate1.txt.gz"));
        Scanner scanner = new Scanner(in);
        while (scanner.hasNextLine()) {
            inputData.add(scanner.nextLine());
        }
        scanner.close();
        return p.apply(Create.of(inputData));
    }

    @Rule public final transient TestPipeline p = TestPipeline.create();

    @Test
    public void noopPipelineTest() throws Exception {
        p.run().waitUntilFinish();
    }

    @Test
    public void countRequestsTest() throws Exception {
        PCollection<String> input = getInput();

        PCollection<Event> events = input.apply(new HTTPRequest.ParseAndWindow(true));
        PCollection<Long> count = events.apply(Combine.globally(Count.<Event>combineFn())
                .withoutDefaults());

        PAssert.that(count)
            .inWindow(new IntervalWindow(new Instant(0L), new Instant(60000)))
            .containsInAnyOrder(720L);
        PAssert.that(count)
            .inWindow(new IntervalWindow(new Instant(300000L), new Instant(360000)))
            .containsInAnyOrder(720L);

        p.run().waitUntilFinish();
    }

    @Test
    public void countInWindowTest() throws Exception {
        ArrayList<KV<String, Long>> expect = new ArrayList<KV<String, Long>>(Arrays.asList(
                KV.of("10.0.0.1", 60L),
                KV.of("10.0.0.2", 60L)
            ));
        PCollection<String> input = getInput();

        PCollection<KV<String, Long>> counts = input.apply(new HTTPRequest.ParseAndWindow(true))
            .apply(new HTTPRequest.CountErrorsInWindow());

        PAssert.that(counts)
            .inWindow(new IntervalWindow(new Instant(0L), new Instant(60000)))
            .containsInAnyOrder(expect);

        p.run().waitUntilFinish();
    }

    @Test
    public void errorRateTest() throws Exception {
        PCollection<String> input = getInput();

        PCollection<Result> results = input.apply(new HTTPRequest.ParseAndWindow(true))
            .apply(new HTTPRequest.CountErrorsInWindow())
            .apply(ParDo.of(new HTTPRequest.ErrorRateAnalysis(30L)));

        PCollection<Long> resultCount = results.apply(Combine.globally(Count.<Result>combineFn())
                .withoutDefaults());
        PAssert.that(resultCount)
            .inWindow(new IntervalWindow(new Instant(300000L), new Instant(360000L)))
            .containsInAnyOrder(2L);

        PAssert.that(results)
            .inWindow(new IntervalWindow(new Instant(300000L), new Instant(360000L)))
            .satisfies(
                i -> {
                    for (Result r : i) {
                        assertThat(r.getSourceAddress(), anyOf(equalTo("10.0.0.1"), equalTo("10.0.0.2")));
                        assertEquals(Result.ResultType.CLIENT_ERROR, r.getResultType());
                        assertEquals(60L, (long)r.getClientErrorCount());
                        assertEquals(30L, (long)r.getMaxClientErrorRate());
                        assertEquals(359999L, r.getWindowTimestamp().getMillis());
                    }
                    return null;
                }
            );

        p.run().waitUntilFinish();
    }
}