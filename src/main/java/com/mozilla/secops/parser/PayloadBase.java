package com.mozilla.secops.parser;

import static com.fasterxml.jackson.annotation.JsonSubTypes.Type;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.Serializable;

/** Base class for payloads */
@JsonTypeInfo(
  use = JsonTypeInfo.Id.NAME,
  include = JsonTypeInfo.As.PROPERTY,
  property = "type_descriptor"
)
@JsonSubTypes({
  @Type(value = Raw.class, name = "raw"),
  @Type(value = Duopull.class, name = "duopull")
})
public abstract class PayloadBase implements Serializable {
  private static final long serialVersionUID = 1L;

  /** Construct matcher object. */
  public PayloadBase() {}

  protected String[] preMatch = null;

  protected void setPreMatch(String[] preMatch) {
    this.preMatch = preMatch;
  }

  /**
   * Attempt prematch before attempting full payload match
   *
   * <p>This is an optimization that can be used in cases where complete match attempts may be
   * costly. If a particular payload type does not set any prematch strings, this function will
   * always return true and a full match will be attempted.
   *
   * @param input Input string
   * @return True if input text matches prematch substrings
   */
  public boolean prematch(String input) {
    if (preMatch == null) {
      // If no prematch strings have been specified for the payload type, always return
      // true so we will attempt a full match.
      return true;
    }
    for (int i = 0; i < preMatch.length; i++) {
      if (input.contains(preMatch[i])) {
        return true;
      }
    }
    return false;
  }

  /**
   * Construct parser object.
   *
   * @param input Input string.
   * @param e Parent {@link Event}.
   * @param state Parser state.
   */
  public PayloadBase(String input, Event e, ParserState state) {}

  /**
   * Apply matcher.
   *
   * @param input Input string.
   * @param state ParserState
   * @return True if matcher matches.
   */
  public Boolean matcher(String input, ParserState state) {
    return false;
  }

  private void setType(String value) {
    // Noop setter, required for event deserialization
  }

  /**
   * Get payload type.
   *
   * @return {@link Payload.PayloadType}
   */
  public Payload.PayloadType getType() {
    return null;
  }

  /**
   * Return a given String payload field value based on the supplied field identifier
   *
   * @param property {@link EventFilterPayload.StringProperty}
   * @return String value or null
   */
  public String eventStringValue(EventFilterPayload.StringProperty property) {
    return null;
  }

  /**
   * Return a given Integer payload field value based on the supplied field identifier
   *
   * @param property {@link EventFilterPayload.IntegerProperty}
   * @return Integer value or null
   */
  public Integer eventIntegerValue(EventFilterPayload.IntegerProperty property) {
    return null;
  }
}
