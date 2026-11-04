<?xml version="1.0" encoding="UTF-8"?>
<!--
  Converts firewall CSV records (Stroom Data Splitter output, namespace records:2)
  into event-logging v4.1.0 Events.

  CSV column order (no header):
    1 timestamp (dd/mm/yyyy HH:mm:ss, assumed UTC)
    2 device name
    3 action (BLOCK, ALLOW or RESET)
    4 source IP address
    5 destination IP address
    6 source port (may be empty for ICMP)
    7 destination port (may be empty for ICMP)
    8 protocol (tcp, udp or icmp)

  A record that fails any check is skipped and its problems are reported with xsl:message.
-->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:rec="records:2"
                xmlns:evt="event-logging:3"
                xmlns:local="urn:stroom:firewall-csv"
                exclude-result-prefixes="rec xs local">

   <xsl:output method="xml" indent="yes" encoding="UTF-8"/>

   <xsl:template match="/">
      <evt:Events Version="4.1.0">
         <xsl:apply-templates select="rec:records/rec:record"/>
      </evt:Events>
   </xsl:template>

   <xsl:template match="rec:record">
      <xsl:variable name="problems" as="xs:string*" select="local:problems(.)"/>
      <xsl:choose>
         <xsl:when test="empty($problems)">
            <xsl:call-template name="event"/>
         </xsl:when>
         <xsl:otherwise>
            <xsl:message>
               <xsl:value-of select="concat('Skipping record ', position(), ': ', string-join($problems, '; '))"/>
            </xsl:message>
         </xsl:otherwise>
      </xsl:choose>
   </xsl:template>

   <!--
     Builds one event-logging Event from a record that has already passed validation.
   -->
   <xsl:template name="event">
      <xsl:variable name="action" as="xs:string" select="local:action(.)"/>
      <xsl:variable name="protocol" as="xs:string" select="local:protocol(.)"/>
      <xsl:variable name="srcIp" as="xs:string" select="local:field(., 4)"/>
      <xsl:variable name="dstIp" as="xs:string" select="local:field(., 5)"/>
      <xsl:variable name="srcPort" as="xs:string" select="local:field(., 6)"/>
      <xsl:variable name="dstPort" as="xs:string" select="local:field(., 7)"/>
      <evt:Event>
         <evt:EventTime>
            <evt:TimeCreated>
               <xsl:value-of select="local:iso-time(local:field(., 1))"/>
            </evt:TimeCreated>
         </evt:EventTime>
         <evt:EventSource>
            <evt:System>
               <evt:Name>Firewall</evt:Name>
               <evt:Environment>Unknown</evt:Environment>
            </evt:System>
            <evt:Generator>Firewall CSV log</evt:Generator>
            <evt:Device>
               <evt:Name>
                  <xsl:value-of select="local:field(., 2)"/>
               </evt:Name>
            </evt:Device>
         </evt:EventSource>
         <evt:EventDetail>
            <evt:TypeId>firewall-traffic</evt:TypeId>
            <evt:Description>
               <xsl:value-of select="concat(local:field(., 2), ' ', $action, ' ', upper-case($protocol), ' ', $srcIp, ' to ', $dstIp)"/>
            </evt:Description>
            <evt:Network>
               <xsl:element name="evt:{local:network-element-name($action)}">
                  <evt:Source>
                     <xsl:call-template name="network-location">
                        <xsl:with-param name="ip" select="$srcIp"/>
                        <xsl:with-param name="port" select="$srcPort"/>
                        <xsl:with-param name="protocol" select="$protocol"/>
                     </xsl:call-template>
                  </evt:Source>
                  <evt:Destination>
                     <xsl:call-template name="network-location">
                        <xsl:with-param name="ip" select="$dstIp"/>
                        <xsl:with-param name="port" select="$dstPort"/>
                        <xsl:with-param name="protocol" select="$protocol"/>
                     </xsl:call-template>
                  </evt:Destination>
                  <xsl:if test="$action = 'BLOCK' or $action = 'ALLOW'">
                     <evt:Outcome>
                        <evt:Permitted>
                           <xsl:value-of select="if ($action = 'ALLOW') then 'true' else 'false'"/>
                        </evt:Permitted>
                     </evt:Outcome>
                  </xsl:if>
               </xsl:element>
            </evt:Network>
         </evt:EventDetail>
      </evt:Event>
   </xsl:template>

   <!--
     Writes the Source or Destination of a network event.
     An empty port is omitted, which is the expected case for ICMP.
   -->
   <xsl:template name="network-location">
      <xsl:param name="ip" as="xs:string"/>
      <xsl:param name="port" as="xs:string"/>
      <xsl:param name="protocol" as="xs:string"/>
      <evt:Device>
         <evt:IPAddress>
            <xsl:value-of select="$ip"/>
         </evt:IPAddress>
      </evt:Device>
      <evt:TransportProtocol>
         <xsl:value-of select="local:transport-protocol($protocol)"/>
      </evt:TransportProtocol>
      <xsl:if test="$port != ''">
         <evt:Port>
            <xsl:value-of select="xs:integer($port)"/>
         </evt:Port>
      </xsl:if>
   </xsl:template>

   <!--
     Returns the problems found in a record. An empty sequence means the record is valid.
   -->
   <xsl:function name="local:problems" as="xs:string*">
      <xsl:param name="record" as="element()"/>
      <xsl:variable name="fieldCount" as="xs:integer" select="count($record/rec:data)"/>
      <xsl:choose>
         <xsl:when test="$fieldCount != 8">
            <xsl:sequence select="concat('expected 8 fields, found ', $fieldCount)"/>
         </xsl:when>
         <xsl:otherwise>
            <xsl:sequence select="(
               if (local:is-timestamp(local:field($record, 1))) then () else concat('invalid timestamp ''', local:field($record, 1), ''''),
               if (local:field($record, 2) != '') then () else 'device name is empty',
               if (local:field($record, 3) = ('BLOCK', 'ALLOW', 'RESET')) then () else concat('unknown action ''', local:field($record, 3), ''''),
               if (local:is-ip-address(local:field($record, 4))) then () else concat('invalid source IP address ''', local:field($record, 4), ''''),
               if (local:is-ip-address(local:field($record, 5))) then () else concat('invalid destination IP address ''', local:field($record, 5), ''''),
               if (local:field($record, 8) = ('tcp', 'udp', 'icmp')) then () else concat('unknown protocol ''', local:field($record, 8), ''''),
               local:port-problems(local:field($record, 6), 'source', local:field($record, 8)),
               local:port-problems(local:field($record, 7), 'destination', local:field($record, 8))
            )"/>
         </xsl:otherwise>
      </xsl:choose>
   </xsl:function>

   <!--
     Returns a problem message for a port value, or an empty sequence if the port is acceptable.
     Ports are required for tcp and udp, and may be empty for icmp.
   -->
   <xsl:function name="local:port-problems" as="xs:string?">
      <xsl:param name="port" as="xs:string"/>
      <xsl:param name="side" as="xs:string"/>
      <xsl:param name="protocol" as="xs:string"/>
      <xsl:choose>
         <xsl:when test="$port = ''">
            <xsl:sequence select="if ($protocol = ('tcp', 'udp')) then concat('missing ', $side, ' port for ', $protocol) else ()"/>
         </xsl:when>
         <xsl:when test="local:is-port($port)">
            <xsl:sequence select="()"/>
         </xsl:when>
         <xsl:otherwise>
            <xsl:sequence select="concat('invalid ', $side, ' port ''', $port, '''')"/>
         </xsl:otherwise>
      </xsl:choose>
   </xsl:function>

   <!--
     Returns the trimmed value of the nth CSV field (1-based), or an empty string if it is absent.
   -->
   <xsl:function name="local:field" as="xs:string">
      <xsl:param name="record" as="element()"/>
      <xsl:param name="position" as="xs:integer"/>
      <xsl:sequence select="normalize-space(string($record/rec:data[$position]/@value))"/>
   </xsl:function>

   <xsl:function name="local:action" as="xs:string">
      <xsl:param name="record" as="element()"/>
      <xsl:sequence select="upper-case(local:field($record, 3))"/>
   </xsl:function>

   <xsl:function name="local:protocol" as="xs:string">
      <xsl:param name="record" as="element()"/>
      <xsl:sequence select="lower-case(local:field($record, 8))"/>
   </xsl:function>

   <!--
     Maps a firewall action to the event-logging Network element:
     BLOCK becomes Deny, ALLOW becomes Permit and RESET becomes Close.
   -->
   <xsl:function name="local:network-element-name" as="xs:string">
      <xsl:param name="action" as="xs:string"/>
      <xsl:sequence select="if ($action = 'ALLOW') then 'Permit' else if ($action = 'BLOCK') then 'Deny' else 'Close'"/>
   </xsl:function>

   <xsl:function name="local:transport-protocol" as="xs:string">
      <xsl:param name="protocol" as="xs:string"/>
      <xsl:sequence select="upper-case($protocol)"/>
   </xsl:function>

   <!--
     Returns true if the value is a date and time in the form dd/mm/yyyy HH:mm:ss.
   -->
   <xsl:function name="local:is-timestamp" as="xs:boolean">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="matches($value, '^\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}$')
         and local:iso-time($value) castable as xs:dateTime"/>
   </xsl:function>

   <!--
     Converts dd/mm/yyyy HH:mm:ss to the UTC form yyyy-mm-ddTHH:mm:ss.000Z required by event-logging.
     The input is assumed to be UTC.
   -->
   <xsl:function name="local:iso-time" as="xs:string">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="concat(substring($value, 7, 4), '-', substring($value, 4, 2), '-', substring($value, 1, 2),
         'T', substring($value, 12, 8), '.000Z')"/>
   </xsl:function>

   <!--
     Returns true if the value is an IPv4 or IPv6 address.
     The IPv6 check is a shape check only and does not reject every malformed address.
   -->
   <xsl:function name="local:is-ip-address" as="xs:boolean">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="matches($value, '^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$')
         or matches($value, '^[0-9A-Fa-f]{0,4}(:[0-9A-Fa-f]{0,4}){2,7}$')"/>
   </xsl:function>

   <!--
     Returns true if the value is a whole number from 0 to 65535.
   -->
   <xsl:function name="local:is-port" as="xs:boolean">
      <xsl:param name="value" as="xs:string"/>
      <xsl:sequence select="if (matches($value, '^\d{1,5}$')) then xs:integer($value) le 65535 else false()"/>
   </xsl:function>

</xsl:stylesheet>
