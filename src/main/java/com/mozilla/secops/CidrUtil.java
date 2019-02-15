package com.mozilla.secops;

import com.mozilla.secops.parser.Event;
import com.mozilla.secops.parser.Normalized;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Scanner;
import org.apache.beam.sdk.transforms.DoFn;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

/** CIDR matching utilities */
public class CidrUtil {
  private ArrayList<IpAddressMatcher> subnets;

  /**
   * Returns a DoFn that filters any events that have a normalized source address field that is
   * within subnets loaded from path.
   *
   * @param path Resource path or GCS URL to load subnets from
   * @return {@link DoFn}
   */
  public static DoFn<Event, Event> excludeNormalizedSourceAddresses(String path) {
    return new DoFn<Event, Event>() {
      private static final long serialVersionUID = 1L;

      private final String resourcePath;
      private CidrUtil cidrs;

      {
        this.resourcePath = path;
      }

      @Setup
      public void setup() throws IOException {
        cidrs = new CidrUtil(resourcePath);
      }

      @ProcessElement
      public void processElement(ProcessContext c) {
        Event e = c.element();
        Normalized n = e.getNormalized();
        if (n != null) {
          String sourceAddress = n.getSourceAddress();
          if (sourceAddress != null) {
            if (cidrs.contains(sourceAddress)) {
              return;
            }
          }
        }
        c.output(e);
      }
    };
  }

  /**
   * Return true if any loaded subnet contains the specified address
   *
   * @param addr IP address to check against subnets
   * @return True if any loaded subnet contains the address
   */
  public Boolean contains(String addr) {
    for (IpAddressMatcher s : subnets) {
      if (s.matches(addr)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Add subnet to subnet list
   *
   * @param cidr Subnet to add
   */
  public void add(String cidr) {
    subnets.add(new IpAddressMatcher(cidr));
  }

  /** Constructor for {@link CidrUtil}, initialize empty */
  public CidrUtil() {
    subnets = new ArrayList<IpAddressMatcher>();
  }

  /**
   * Constructor for {@link CidrUtil} to load subnet list from resource
   *
   * @param path Resource path or GCS URL to load CIDR subnet list from
   */
  public CidrUtil(String path) throws IOException {
    this();
    InputStream in;
    if (GcsUtil.isGcsUrl(path)) {
      in = GcsUtil.fetchInputStreamContent(path);
    } else {
      in = CidrUtil.class.getResourceAsStream(path);
    }
    if (in == null) {
      throw new IOException("failed to load cidr list from specified path");
    }
    Scanner s = new Scanner(in);
    while (s.hasNext()) {
      add(s.next());
    }
  }
}
