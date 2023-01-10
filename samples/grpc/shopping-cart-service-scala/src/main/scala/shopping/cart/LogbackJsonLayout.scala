/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */
package shopping.cart

import ch.qos.logback.contrib.json.classic.JsonLayout

class LogbackJsonLayout extends JsonLayout {
  setIncludeLevel(false)
  setIncludeThreadName(false)
  setIncludeContextName(false)
  setTimestampFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX")
  setTimestampFormatTimezoneId("Etc/UTC")
  setAppendLineSeparator(true)

  override def addCustomDataToJsonMap(
      map: java.util.Map[String, AnyRef],
      event: ch.qos.logback.classic.spi.ILoggingEvent): Unit = {
    add("severity", true, String.valueOf(event.getLevel), map)

    val marker = event.getMarker
    if (marker ne null)
      add("tags", true, marker.getName, map)

    val mdc = event.getMDCPropertyMap
    // use the Akka thread if available
    val thread = mdc.getOrDefault("sourceThread", event.getThreadName)
    add("thread", true, thread, map)
  }

}
