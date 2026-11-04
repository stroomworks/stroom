<?xml version="1.0" encoding="UTF-8"?>
<!--
  Translates firewall CSV records (Data Splitter output, records:2) into
  event-logging v4.1.0 Events.

  Input columns, in CSV order (no header):
    1 timestamp (dd/mm/yyyy hh:mm:ss, UTC)
    2 device name
    3 action (BLOCK, ALLOW or RESET)
    4 source IP
    5 destination IP
    6 source port (optional)
    7 destination port (optional)
    8 protocol (tcp, udp or icmp)

  Records that fail validation produce no Event and an xsl:message.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:evt="event-logging:3"
                xmlns:local="urn:stroom:firewall-translation"
                exclude-result-prefixes="rec xs local">

   <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

   <xsl:template match="/">
      <evt:Events Version="4.1.0">
         <xsl:for-each select="rec:records/rec:record">
            <xsl:variable name="problem" as="xs:string"
                          select="local:problem(.)"/>
            <xsl:choose>
               <xsl:when test="$problem = ''">
                  <xsl:call-template name="event">
                     <xsl:with-param name="record" select="."/>
                  </xsl:call-template>
               </xsl:when>
               <xsl:otherwise>
                  <xsl:message>
                     <xsl:value-of select="concat('Dropped record ', position(),
                                           ' (', $problem, '): ',
                                           string-join(rec:data/@value, ','))"/>
                  </xsl:message>
               </xsl:otherwise>
            </xsl:choose>
         </xsl:for-each>
      </evt:Events>
   </xsl:template>

   <!-- Builds one evt:Event from a valid record. -->
   <xsl:template name="event">
      <xsl:param name="record" as="element(rec:record)" required="yes"/>
      <xsl:variable name="action" as="xs:string"
                    select="upper-case(local:field($record, 3))"/>
      <xsl:variable name="protocol" as="xs:string"
                    select="upper-case(local:field($record, 8))"/>
      <evt:Event>
         <evt:EventTime>
            <evt:TimeCreated>
               <xsl:value-of select="local:iso-time(local:field($record, 1))"/>
            </evt:TimeCreated>
         </evt:EventTime>
         <evt:EventSource>
            <evt:System>
               <evt:Name>Firewall</evt:Name>
               <evt:Environment>Unknown</evt:Environment>
            </evt:System>
            <evt:Generator>Firewall</evt:Generator>
            <evt:Device>
               <evt:HostName>
                  <xsl:value-of select="local:field($record, 2)"/>
               </evt:HostName>
            </evt:Device>
         </evt:EventSource>
         <evt:EventDetail>
            <evt:TypeId>Firewall Traffic</evt:TypeId>
            <evt:Network>
               <xsl:element name="evt:{local:action-element($action)}"
                            namespace="event-logging:3">
                  <evt:Source>
                     <xsl:call-template name="location">
                        <xsl:with-param name="ip" select="local:field($record, 4)"/>
                        <xsl:with-param name="port" select="local:field($record, 6)"/>
                        <xsl:with-param name="protocol" select="$protocol"/>
                     </xsl:call-template>
                  </evt:Source>
                  <evt:Destination>
                     <xsl:call-template name="location">
                        <xsl:with-param name="ip" select="local:field($record, 5)"/>
                        <xsl:with-param name="port" select="local:field($record, 7)"/>
                        <xsl:with-param name="protocol" select="$protocol"/>
                     </xsl:call-template>
                  </evt:Destination>
               </xsl:element>
            </evt:Network>
         </evt:EventDetail>
      </evt:Event>
   </xsl:template>

   <!-- Builds a Network Source or Destination: device IP, protocol and optional port. -->
   <xsl:template name="location">
      <xsl:param name="ip" as="xs:string" required="yes"/>
      <xsl:param name="port" as="xs:string" required="yes"/>
      <xsl:param name="protocol" as="xs:string" required="yes"/>
      <evt:Device>
         <evt:IPAddress>
            <xsl:value-of select="$ip"/>
         </evt:IPAddress>
      </evt:Device>
      <evt:TransportProtocol>
         <xsl:value-of select="$protocol"/>
      </evt:TransportProtocol>
      <xsl:if test="$port != ''">
         <evt:Port>
            <xsl:value-of select="xs:integer($port)"/>
         </evt:Port>
      </xsl:if>
   </xsl:template>

   <!-- Returns the trimmed value of the nth data field, or an empty string if absent. -->
   <xsl:function name="local:field" as="xs:string">
      <xsl:param name="record" as="element(rec:record)"/>
      <xsl:param name="n" as="xs:integer"/>
      <xsl:sequence select="normalize-space(string($record/rec:data[$n]/@value))"/>
   </xsl:function>

   <!-- Maps BLOCK to Deny, ALLOW to Permit and RESET to Close. -->
   <xsl:function name="local:action-element" as="xs:string">
      <xsl:param name="action" as="xs:string"/>
      <xsl:sequence select="if ($action = 'BLOCK') then 'Deny'
                            else if ($action = 'ALLOW') then 'Permit'
                            else 'Close'"/>
   </xsl:function>

   <!-- Converts dd/mm/yyyy hh:mm:ss (UTC) to yyyy-mm-ddThh:mm:ss.000Z. -->
   <xsl:function name="local:iso-time" as="xs:string">
      <xsl:param name="ts" as="xs:string"/>
      <xsl:sequence select="replace($ts,
                            '^(\d{2})/(\d{2})/(\d{4}) (\d{2}:\d{2}:\d{2})$',
                            '$3-$2-$1T$4.000Z')"/>
   </xsl:function>

   <!-- Returns a reason the record is invalid, or an empty string if it is valid. -->
   <xsl:function name="local:problem" as="xs:string">
      <xsl:param name="record" as="element(rec:record)"/>
      <xsl:variable name="fieldCount" as="xs:integer" select="count($record/rec:data)"/>
      <xsl:sequence select="
            if ($fieldCount != 8) then
               concat('expected 8 fields, found ', $fieldCount)
            else if (not(local:is-valid-timestamp(local:field($record, 1)))) then
               'invalid timestamp'
            else if (local:field($record, 2) = '') then
               'missing device name'
            else if (not(upper-case(local:field($record, 3)) = ('BLOCK', 'ALLOW', 'RESET'))) then
               concat('unknown action ', local:field($record, 3))
            else if (not(local:is-ip(local:field($record, 4)))) then
               concat('invalid source IP ', local:field($record, 4))
            else if (not(local:is-ip(local:field($record, 5)))) then
               concat('invalid destination IP ', local:field($record, 5))
            else if (not(local:is-valid-port(local:field($record, 6)))) then
               concat('invalid source port ', local:field($record, 6))
            else if (not(local:is-valid-port(local:field($record, 7)))) then
               concat('invalid destination port ', local:field($record, 7))
            else if (not(upper-case(local:field($record, 8)) = ('TCP', 'UDP', 'ICMP'))) then
               concat('unknown protocol ', local:field($record, 8))
            else ''"/>
   </xsl:function>

   <!-- True if the value is a dd/mm/yyyy hh:mm:ss timestamp with a real calendar date and time. -->
   <xsl:function name="local:is-valid-timestamp" as="xs:boolean">
      <xsl:param name="ts" as="xs:string"/>
      <xsl:choose>
         <xsl:when test="not(matches($ts, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$'))">
            <xsl:sequence select="false()"/>
         </xsl:when>
         <xsl:otherwise>
            <xsl:variable name="day" as="xs:integer" select="xs:integer(substring($ts, 1, 2))"/>
            <xsl:variable name="month" as="xs:integer" select="xs:integer(substring($ts, 4, 2))"/>
            <xsl:variable name="year" as="xs:integer" select="xs:integer(substring($ts, 7, 4))"/>
            <xsl:variable name="hour" as="xs:integer" select="xs:integer(substring($ts, 12, 2))"/>
            <xsl:variable name="minute" as="xs:integer" select="xs:integer(substring($ts, 15, 2))"/>
            <xsl:variable name="second" as="xs:integer" select="xs:integer(substring($ts, 18, 2))"/>
            <xsl:sequence select="$month ge 1 and $month le 12
                  and $day ge 1 and $day le local:days-in-month($year, $month)
                  and $hour le 23 and $minute le 59 and $second le 59"/>
         </xsl:otherwise>
      </xsl:choose>
   </xsl:function>

   <!-- Number of days in a month, allowing for leap years. -->
   <xsl:function name="local:days-in-month" as="xs:integer">
      <xsl:param name="year" as="xs:integer"/>
      <xsl:param name="month" as="xs:integer"/>
      <xsl:variable name="leap" as="xs:boolean"
                    select="($year mod 4 = 0 and $year mod 100 != 0) or $year mod 400 = 0"/>
      <xsl:sequence select="if ($month = 2) then (if ($leap) then 29 else 28)
                            else if ($month = 4 or $month = 6 or $month = 9 or $month = 11) then 30
                            else 31"/>
   </xsl:function>

   <!-- True if the value is an IPv4 address or a colon-separated IPv6 address. -->
   <xsl:function name="local:is-ip" as="xs:boolean">
      <xsl:param name="ip" as="xs:string"/>
      <xsl:sequence select="matches($ip,
            '^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$')
            or (contains($ip, ':') and matches($ip, '^[0-9A-Fa-f:]+$'))"/>
   </xsl:function>

   <!-- True if the port is empty (omitted) or an integer from 0 to 65535. -->
   <xsl:function name="local:is-valid-port" as="xs:boolean">
      <xsl:param name="port" as="xs:string"/>
      <xsl:sequence select="
            if ($port = '') then true()
            else if (not(matches($port, '^\d{1,5}$'))) then false()
            else xs:integer($port) le 65535"/>
   </xsl:function>

</xsl:stylesheet>
