package com.mozilla.secops.parser;

import org.junit.Test;
import org.junit.Rule;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.transforms.Combine;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.values.KV;

public class EventFilterTransformTest {
    public EventFilterTransformTest() {
    }

    @Rule public final transient TestPipeline pipeline = TestPipeline.create();

    @Test
    public void testTransformPayloadMatch() throws Exception {
        Parser p = new Parser();
        Event e = p.parse("picard");
        assertNotNull(e);
        PCollection<Event> input = pipeline.apply(Create.of(e));

        EventFilter pFilter = new EventFilter();
        assertNotNull(pFilter);
        pFilter.addRule(new EventFilterRule()
            .wantSubtype(Payload.PayloadType.RAW));

        EventFilter nFilter = new EventFilter();
        assertNotNull(nFilter);
        nFilter.addRule(new EventFilterRule()
            .wantSubtype(Payload.PayloadType.GLB));

        PCollection<Event> pfiltered = input.apply("positive", EventFilter.getTransform(pFilter));
        PCollection<Event> nfiltered = input.apply("negative", EventFilter.getTransform(nFilter));

        PCollection<Long> pcount = pfiltered.apply("pcount", Count.globally());
        PAssert.that(pcount).containsInAnyOrder(1L);

        PCollection<Long> ncount = nfiltered.apply("ncount", Count.globally());
        PAssert.that(ncount).containsInAnyOrder(0L);

        pipeline.run().waitUntilFinish();
    }

    @Test
    public void testTransformPayloadMatchRaw() throws Exception {
        Parser p = new Parser();
        Event e = p.parse("picard");
        assertNotNull(e);
        PCollection<Event> input = pipeline.apply(Create.of(e));

        EventFilter pFilter = new EventFilter();
        assertNotNull(pFilter);
        pFilter.addRule(new EventFilterRule()
            .wantSubtype(Payload.PayloadType.RAW)
            .addPayloadFilter(new EventFilterPayload(Raw.class)
                .withStringMatch(EventFilterPayload.StringProperty.RAW_RAW, "picard")));

        EventFilter nFilter = new EventFilter();
        assertNotNull(nFilter);
        nFilter.addRule(new EventFilterRule()
            .wantSubtype(Payload.PayloadType.RAW)
            .addPayloadFilter(new EventFilterPayload(Raw.class)
                .withStringMatch(EventFilterPayload.StringProperty.RAW_RAW, "jean-luc")));

        PCollection<Event> pfiltered = input.apply("positive", EventFilter.getTransform(pFilter));
        PCollection<Event> nfiltered = input.apply("negative", EventFilter.getTransform(nFilter));

        PCollection<Long> pcount = pfiltered.apply("pcount", Count.globally());
        PAssert.that(pcount).containsInAnyOrder(1L);

        PCollection<Long> ncount = nfiltered.apply("ncount", Count.globally());
        PAssert.that(ncount).containsInAnyOrder(0L);

        pipeline.run().waitUntilFinish();
    }

    @Test
    public void testTransformKeying() throws Exception {
        String buf = "{\"secevent_version\":\"secevent.model.1\",\"action\":\"loginFailure\"" +
            ",\"account_id\":\"q@the-q-continuum\",\"timestamp\":\"1970-01-01T00:00:00+00:00\"}";
        Parser p = new Parser();
        assertNotNull(p);
        Event e = p.parse(buf);
        assertNotNull(e);
        PCollection<Event> input = pipeline.apply(Create.of(e));

        EventFilter filter = new EventFilter().matchAny();
        assertNotNull(filter);
        filter.addKeyingSelector(new EventFilterRule()
            .wantSubtype(Payload.PayloadType.SECEVENT)
            .addPayloadFilter(new EventFilterPayload(SecEvent.class)
                .withStringSelector(EventFilterPayload.StringProperty.SECEVENT_ACTION)));

        EventFilter multiFilter = new EventFilter().matchAny();
        assertNotNull(multiFilter);
        multiFilter.addKeyingSelector(new EventFilterRule()
            .wantSubtype(Payload.PayloadType.SECEVENT)
            .addPayloadFilter(new EventFilterPayload(SecEvent.class)
                .withStringSelector(EventFilterPayload.StringProperty.SECEVENT_ACTION)
                .withStringSelector(EventFilterPayload.StringProperty.SECEVENT_ACCOUNTID)));

        PCollection<KV<String, Event>> keyed = input.apply("filter", EventFilter.getKeyingTransform(filter));
        PCollection<KV<String, Event>> multiKeyed =
            input.apply("multiFilter", EventFilter.getKeyingTransform(multiFilter));

        PAssert.thatMap(keyed).satisfies(
            results -> {
                Event ev = results.get("loginFailure");
                assertNotNull(ev);
                ev = results.get("secevent.model.1");
                assertNull(ev);
                return null;
            });

        PAssert.thatMap(multiKeyed).satisfies(
            results -> {
                Event ev = results.get("loginFailure+q@the-q-continuum");
                assertNotNull(ev);
                ev = results.get("loginFailure");
                assertNull(ev);
                return null;
            });

        pipeline.run().waitUntilFinish();
    }
}
