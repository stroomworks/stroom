<?xml version="1.0" encoding="UTF-8"?>
<!--
  Converts Data Splitter output (records:2) for a firewall log into
  event-logging v4.1.0 Events. Records that are not valid firewall
  records (comments, garbled lines, bad timestamps, bad addresses,
  unknown actions) are skipped.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:local="urn:stroom-pilot:firewall-local"
                xmlns="event-logging:3"
                xpath-default-namespace="records:2"
                exclude-result-prefixes="xs local">

   <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

   <xsl:template match="/">
      <Events Version="4.1.0">
         <xsl:apply-templates select="/*/record[local:is-valid-record(.)]" mode="event"/>
      </Events>
   </xsl:template>

   <xsl:template match="record" mode="event">
      <xsl:variable name="action" select="local:field(., 'action')" as="xs:string"/>
      <xsl:variable name="permitted" select="$action = 'ACCEPT'" as="xs:boolean"/>
      <Event>
         <EventTime>
            <TimeCreated>
               <xsl:value-of select="local:utc-time(local:field(., 'time'))"/>
            </TimeCreated>
         </EventTime>
         <EventSource>
            <System>
               <Name>Firewall</Name>
               <Environment>Unknown</Environment>
            </System>
            <Generator>Firewall log</Generator>
            <Device>
               <HostName>
                  <xsl:value-of select="local:field(., 'host')"/>
               </HostName>
            </Device>
         </EventSource>
         <EventDetail>
            <TypeId>FirewallTraffic</TypeId>
            <Network>
               <xsl:element name="{if ($permitted) then 'Permit' else 'Deny'}">
                  <Source>
                     <Device>
                        <IPAddress>
                           <xsl:value-of select="local:field(., 'src')"/>
                        </IPAddress>
                     </Device>
                     <TransportProtocol>
                        <xsl:value-of select="local:protocol(local:field(., 'proto'))"/>
                     </TransportProtocol>
                     <xsl:if test="local:is-port(local:field(., 'sport'))">
                        <Port>
                           <xsl:value-of select="local:field(., 'sport')"/>
                        </Port>
                     </xsl:if>
                  </Source>
                  <xsl:if test="local:is-ip(local:field(., 'dst')) or local:is-port(local:field(., 'dport'))">
                     <Destination>
                        <xsl:if test="local:is-ip(local:field(., 'dst'))">
                           <Device>
                              <IPAddress>
                                 <xsl:value-of select="local:field(., 'dst')"/>
                              </IPAddress>
                           </Device>
                        </xsl:if>
                        <TransportProtocol>
                           <xsl:value-of select="local:protocol(local:field(., 'proto'))"/>
                        </TransportProtocol>
                        <xsl:if test="local:is-port(local:field(., 'dport'))">
                           <Port>
                              <xsl:value-of select="local:field(., 'dport')"/>
                           </Port>
                        </xsl:if>
                     </Destination>
                  </xsl:if>
                  <Outcome>
                     <Permitted>
                        <xsl:value-of select="if ($permitted) then 'true' else 'false'"/>
                     </Permitted>
                     <Description>
                        <xsl:value-of select="concat('Firewall action ', $action)"/>
                     </Description>
                  </Outcome>
               </xsl:element>
            </Network>
         </EventDetail>
      </Event>
   </xsl:template>

   <!-- Returns the value of the named data field, or an empty sequence if absent or empty. -->
   <xsl:function name="local:field" as="xs:string?">
      <xsl:param name="rec" as="element()"/>
      <xsl:param name="name" as="xs:string"/>
      <xsl:sequence select="string(($rec/data[@name = $name]/@value)[1])[string-length(.) gt 0]"/>
   </xsl:function>

   <xsl:function name="local:is-valid-record" as="xs:boolean">
      <xsl:param name="rec" as="element()"/>
      <xsl:variable name="host" select="local:field($rec, 'host')" as="xs:string?"/>
      <xsl:variable name="proto" select="local:field($rec, 'proto')" as="xs:string?"/>
      <xsl:sequence select="local:is-valid-time(local:field($rec, 'time'))
                            and exists($host) and matches($host, '^[A-Za-z0-9._-]+$')
                            and local:field($rec, 'action') = ('ACCEPT', 'DROP')
                            and local:is-ip(local:field($rec, 'src'))
                            and (empty(local:field($rec, 'dst')) or local:field($rec, 'dst') = '-' or local:is-ip(local:field($rec, 'dst')))
                            and exists($proto) and matches($proto, '^[A-Z0-9]+$')
                            and (empty(local:field($rec, 'sport')) or local:field($rec, 'sport') = '-' or local:is-port(local:field($rec, 'sport')))
                            and (empty(local:field($rec, 'dport')) or local:field($rec, 'dport') = '-' or local:is-port(local:field($rec, 'dport')))"/>
   </xsl:function>

   <!-- Accepts ISO 8601 date-times with an explicit timezone designator, e.g. 2026-11-04T10:00:01Z or ...+01:00. -->
   <xsl:function name="local:is-valid-time" as="xs:boolean">
      <xsl:param name="value" as="xs:string?"/>
      <xsl:sequence select="exists($value)
                            and matches($value, '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(Z|[+-][0-9]{2}:[0-9]{2})$')
                            and $value castable as xs:dateTime"/>
   </xsl:function>

   <!-- Converts a valid date-time to UTC in the event-logging format YYYY-MM-DDThh:mm:ss.sssZ. -->
   <xsl:function name="local:utc-time" as="xs:string">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="format-dateTime(adjust-dateTime-to-timezone(xs:dateTime($value), xs:dayTimeDuration('PT0S')), '[Y0001]-[M01]-[D01]T[H01]:[m01]:[s01].000Z')"/>
   </xsl:function>

   <xsl:function name="local:is-ip" as="xs:boolean">
      <xsl:param name="value" as="xs:string?"/>
      <xsl:sequence select="local:is-ipv4($value) or local:is-ipv6($value)"/>
   </xsl:function>

   <xsl:function name="local:is-ipv4" as="xs:boolean">
      <xsl:param name="value" as="xs:string?"/>
      <xsl:sequence select="exists($value)
                            and matches($value, '^((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])$')"/>
   </xsl:function>

   <!-- A deliberately simple IPv6 check: hex groups of 1-4 digits, at most one '::'. -->
   <xsl:function name="local:is-ipv6" as="xs:boolean">
      <xsl:param name="value" as="xs:string?"/>
      <xsl:sequence select="exists($value)
                            and matches($value, '^[0-9A-Fa-f:]+$')
                            and contains($value, ':')
                            and not(contains($value, ':::'))
                            and count(tokenize($value, '::')) le 2
                            and count(tokenize($value, ':')[. ne '']) le 8
                            and (every $group in tokenize($value, ':') satisfies ($group = '' or matches($group, '^[0-9A-Fa-f]{1,4}$')))"/>
   </xsl:function>

   <xsl:function name="local:is-port" as="xs:boolean">
      <xsl:param name="value" as="xs:string?"/>
      <xsl:sequence select="exists($value)
                            and matches($value, '^[0-9]{1,5}$')
                            and xs:integer($value) le 65535"/>
   </xsl:function>

   <!-- Maps the protocol to the event-logging enumeration; anything not listed becomes Other. -->
   <xsl:function name="local:protocol" as="xs:string">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="if ($value = ('TCP', 'UDP', 'ICMP', 'IGMP')) then $value else 'Other'"/>
   </xsl:function>

</xsl:stylesheet>
