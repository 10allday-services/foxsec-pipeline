package com.mozilla.secops;

import com.mozilla.secops.alert.Alert;
import com.mozilla.secops.alert.AlertMeta;
import com.mozilla.secops.httprequest.HTTPRequestToggles;
import com.mozilla.secops.parser.Event;
import com.mozilla.secops.parser.Normalized;
import com.mozilla.secops.window.GlobalTriggers;
import java.io.Serializable;
import java.util.Objects;
import org.apache.avro.reflect.Nullable;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.DefaultCoder;
import org.apache.beam.sdk.transforms.Distinct;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.GroupByKey;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.Duration;

/**
 * Source address ingestion and alert correlation
 *
 * <p>Correlates observed source addresses extracted from normalized event field with source
 * addresses that are associated with alerts.
 */
public class SourceCorrelation {
  /**
   * SourceData is an intermediate format used to store information about a given source address
   * observed in the ingestion or alert stream.
   *
   * <p>{@link AvroCoder} is used to guarantee certain uniqueness properties that are needed during
   * the {@link Distinct} step. In this case Serializable does not guarantee us deterministic
   * encoding.
   *
   * <p>See also
   * https://beam.apache.org/releases/javadoc/2.17.0/org/apache/beam/sdk/coders/Coder.html#verifyDeterministic--
   */
  @DefaultCoder(AvroCoder.class)
  public static class SourceData implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Source data types */
    public enum SourceDataType {
      SOURCE_DATA_TYPE_ALERT,
      SOURCE_DATA_TYPE_EVENT
    }

    private String sourceAddress;
    @Nullable private String city;
    @Nullable private String country;
    @Nullable private Integer asn;
    @Nullable private String isp;
    private SourceDataType type;

    /**
     * Get source data type
     *
     * @return SourceDataType
     */
    public SourceDataType getSourceDataType() {
      return type;
    }

    /**
     * Set source data type
     *
     * @param type SourceDataType
     */
    public void setSourceDataType(SourceDataType type) {
      this.type = type;
    }

    /**
     * Get source address
     *
     * @return String
     */
    public String getSourceAddress() {
      return sourceAddress;
    }

    /**
     * Set source address
     *
     * @param sourceAddress String
     */
    public void setSourceAddress(String sourceAddress) {
      this.sourceAddress = sourceAddress;
    }

    /**
     * Get city
     *
     * @return String
     */
    public String getCity() {
      return city;
    }

    /**
     * Set city
     *
     * @param city String
     */
    public void setCity(String city) {
      this.city = city;
    }

    /**
     * Get country
     *
     * @return String
     */
    public String getCountry() {
      return country;
    }

    /**
     * Set country
     *
     * @param country String
     */
    public void setCountry(String country) {
      this.country = country;
    }

    /**
     * Get ASN
     *
     * @return Integer
     */
    public Integer getAsn() {
      return asn;
    }

    /**
     * Set ASN
     *
     * @param asn Integer
     */
    public void setAsn(Integer asn) {
      this.asn = asn;
    }

    /**
     * Get ISP
     *
     * @return String
     */
    public String getIsp() {
      return isp;
    }

    /**
     * Set ISP
     *
     * @param isp String
     */
    public void setIsp(String isp) {
      this.isp = isp;
    }

    @Override
    public boolean equals(Object o) {
      if (o == this) {
        return true;
      }
      if (!(o instanceof SourceData)) {
        return false;
      }
      SourceData d = (SourceData) o;
      return sourceAddress.equals(d.getSourceAddress()) && type.equals(d.getSourceDataType());
    }

    @Override
    public int hashCode() {
      return Objects.hash(sourceAddress, type);
    }
  }

  /**
   * Transform for source address alert and ingestion correlation
   *
   * <p>This transform can be used to correlate source address information from an ingestion event
   * stream with source address information that is present in alerts generated by the pipeline.
   *
   * <p>Currently this transform is limited to ISP based correlation. Alerts are grouped by ISP
   * based on distinct source address, and are compared with source address and ISP information
   * grouped in a similar way from the ingestion event stream.
   *
   * <p>If at least a certain number of addresses are seen associated with a given ISP, and the
   * percentage of these addresses that generated an alert in a fixed window exceeds the configured
   * value, an alert is generated.
   *
   * <p>Currently uses a hardcoded fixed window value of 6 hours.
   */
  public static class SourceCorrelator
      extends PTransform<PCollection<SourceData>, PCollection<Alert>>
      implements DocumentingTransform {
    private static final long serialVersionUID = 1L;

    private final String monitoredResource;
    private final int sourceMinimum;
    private final double alertPercentage;

    /**
     * Initialize new SourceCorrelator
     *
     * @param toggles HTTPRequestToggles
     */
    public SourceCorrelator(HTTPRequestToggles toggles) {
      this.sourceMinimum = toggles.getSourceCorrelatorMinimumAddresses();
      this.alertPercentage = toggles.getSourceCorrelatorAlertPercentage();
      this.monitoredResource = toggles.getMonitoredResource();
    }

    /** {@inheritDoc} */
    public String getTransformDoc() {
      return String.format(
          "Source address alerting correlation, ISP analysis on minimum %d "
              + "addresses at %.2f alerting percentage.",
          sourceMinimum, alertPercentage);
    }

    @Override
    public PCollection<Alert> expand(PCollection<SourceData> input) {
      return input
          .apply(
              Window.<SourceData>into(FixedWindows.of(Duration.standardHours(6)))
                  .withAllowedLateness(Duration.ZERO))
          .apply(Distinct.<SourceData>create())
          .apply(
              "extract resolved isp",
              ParDo.of(
                  new DoFn<SourceData, KV<String, SourceData>>() {
                    private static final long serialVersionUID = 1L;

                    @ProcessElement
                    public void processElement(ProcessContext c) {
                      if (c.element().getIsp() == null) {
                        return;
                      }
                      c.output(KV.of(c.element().getIsp(), c.element()));
                    }
                  }))
          .apply(GroupByKey.<String, SourceData>create())
          .apply(
              "source correlator isp analyze",
              ParDo.of(
                  new DoFn<KV<String, Iterable<SourceData>>, Alert>() {
                    private static final long serialVersionUID = 1L;

                    @ProcessElement
                    public void processElement(ProcessContext c) {
                      int cnt = 0;
                      int alert = 0;
                      for (SourceData s : c.element().getValue()) {
                        if (s.getSourceDataType()
                            .equals(SourceData.SourceDataType.SOURCE_DATA_TYPE_ALERT)) {
                          alert++;
                        } else {
                          cnt++;
                        }
                      }
                      if (cnt < sourceMinimum) {
                        return;
                      }
                      double p = (double) alert / cnt * 100.0;
                      if (p < alertPercentage) {
                        return;
                      }
                      String isp = c.element().getKey();
                      Alert a = new Alert();
                      a.setSummary(
                          String.format(
                              "%s httprequest isp_source_correlation "
                                  + "\"%s\", %d alerting addresses out of %d observed",
                              monitoredResource, isp, alert, cnt));
                      a.setCategory("httprequest");
                      a.setSubcategory("isp_source_correlation");
                      a.addMetadata(AlertMeta.Key.SOURCEADDRESS_ISP, isp);
                      a.addMetadata(AlertMeta.Key.TOTAL_ADDRESS_COUNT, Integer.toString(cnt));
                      a.addMetadata(AlertMeta.Key.TOTAL_ALERT_COUNT, Integer.toString(alert));
                      a.setNotifyMergeKey(
                          String.format("%s isp_source_correlation", monitoredResource));
                      c.output(a);
                    }
                  }))
          .apply(new GlobalTriggers<Alert>(5));
    }
  }

  /**
   * Convert {@link Event} to {@link SourceData}
   *
   * <p>Note this function will modify the element timestamp, and set it to current system clock.
   */
  public static class EventSourceExtractor extends DoFn<Event, SourceData> {
    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext c) {
      Normalized n = c.element().getNormalized();
      if (n.getSourceAddress() == null) {
        return;
      }
      SourceData o = new SourceData();
      o.setSourceAddress(n.getSourceAddress());
      o.setCity(n.getSourceAddressCity());
      o.setCountry(n.getSourceAddressCountry());
      o.setIsp(n.getSourceAddressIsp());
      o.setAsn(n.getSourceAddressAsn());
      o.setSourceDataType(SourceData.SourceDataType.SOURCE_DATA_TYPE_EVENT);
      c.output(o);
    }
  }

  /**
   * Convert {@link Alert} to {@link SourceData}
   *
   * <p>Note this function will modify the element timestamp, and set it to current system clock.
   */
  public static class AlertSourceExtractor extends DoFn<Alert, SourceData> {
    private static final long serialVersionUID = 1L;

    @ProcessElement
    public void processElement(ProcessContext c) {
      Alert a = c.element();
      if (a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS) == null) {
        return;
      }
      SourceData o = new SourceData();
      o.setSourceAddress(a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS));
      o.setCity(a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_CITY));
      o.setCountry(a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_COUNTRY));
      o.setIsp(a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_ISP));
      String asns = a.getMetadataValue(AlertMeta.Key.SOURCEADDRESS_ASN);
      if (asns != null) {
        o.setAsn(new Integer(asns));
      }
      o.setSourceDataType(SourceData.SourceDataType.SOURCE_DATA_TYPE_ALERT);
      c.output(o);
    }
  }
}
