package com.mozilla.secops;

import com.mozilla.secops.parser.Event;
import com.mozilla.secops.parser.Normalized;
import java.util.Map;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TypeDescriptors;

/**
 * Provides a basic NAT detection transform
 *
 * <p>Currently this transform only operates on normalized {@link
 * com.mozilla.secops.parser.Normalized.Type#HTTP_REQUEST} events and is based on the detection of
 * multiple user agents being identified for requests within the window for the same source IP
 * address.
 *
 * <p>The output from the transform is a {@link PCollection} of KV pairs where the key is a source
 * IP address identified in the window and the value is a boolean set to true if there is a
 * possibility the source address is a NAT gateway. If it is not suspected the source address is a
 * NAT gateway, it will not be included in the output set.
 */
public class DetectNat extends PTransform<PCollection<Event>, PCollection<KV<String, Boolean>>> {
  private static final long serialVersionUID = 1L;

  private static final Long UAMARKPROBABLE = 2L;

  private final String stepPrefix;

  /**
   * Create new DetectNat
   *
   * @param stepPrefix Prefix for transform step or null for none
   */
  public DetectNat(String stepPrefix) {
    this.stepPrefix = stepPrefix;
  }

  /** Create new DetectNat */
  public DetectNat() {
    this(null);
  }

  /**
   * Return an empty NAT view, suitable as a placeholder if NAT detection is not desired
   *
   * @param p Pipeline to create view for
   * @return Empty {@link PCollectionView}
   */
  public static PCollectionView<Map<String, Boolean>> getEmptyView(Pipeline p) {
    return p.apply(
            "empty nat view",
            Create.empty(
                TypeDescriptors.kvs(TypeDescriptors.strings(), TypeDescriptors.booleans())))
        .apply(View.<String, Boolean>asMap());
  }

  /**
   * Execute the transform returning a {@link PCollectionView} suitable for use as a side input
   *
   * @param events Input events
   * @param prefix Prefix for step name or null for none
   * @return {@link PCollectionView} representing output of analysis
   */
  public static PCollectionView<Map<String, Boolean>> getView(
      PCollection<Event> events, String prefix) {
    if (prefix == null) {
      prefix = "";
    }
    return events
        .apply(String.format("%s nat view", prefix), new DetectNat(prefix))
        .apply(String.format("%s nat map view", prefix), View.<String, Boolean>asMap());
  }

  @Override
  public PCollection<KV<String, Boolean>> expand(PCollection<Event> events) {
    String p = "";
    if (stepPrefix != null) {
      p = stepPrefix;
    }
    PCollection<KV<String, Long>> perSourceUACounts =
        events
            .apply(
                String.format("%s detectnat extract user agents", p),
                ParDo.of(
                    new DoFn<Event, KV<String, String>>() {
                      private static final long serialVersionUID = 1L;

                      @ProcessElement
                      public void processElement(ProcessContext c) {
                        Event e = c.element();

                        Normalized n = e.getNormalized();
                        if (n.isOfType(Normalized.Type.HTTP_REQUEST)) {
                          if (n.getSourceAddress() != null && n.getUserAgent() != null) {
                            c.output(KV.of(n.getSourceAddress(), n.getUserAgent()));
                          }
                        } else {
                          return;
                        }
                      }
                    }))
            .apply(
                String.format("%s detectnat distinct ua map", p),
                Distinct.<KV<String, String>>create())
            .apply(
                String.format("%s detectnat ua count per key", p), Count.<String, String>perKey());

    // Operate solely on the UA output right now here, but this should be expanded with more
    // detailed analysis
    return perSourceUACounts.apply(
        String.format("%s detect nat", p),
        ParDo.of(
            new DoFn<KV<String, Long>, KV<String, Boolean>>() {
              private static final long serialVersionUID = 1L;

              @ProcessElement
              public void processElement(ProcessContext c) {
                KV<String, Long> input = c.element();
                if (input.getValue() >= UAMARKPROBABLE) {
                  c.output(KV.of(input.getKey(), true));
                }
              }
            }));
  }
}
