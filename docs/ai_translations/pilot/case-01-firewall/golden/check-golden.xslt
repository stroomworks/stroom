<?xml version="1.0" encoding="UTF-8"?>
<!-- Checks a candidate translation's output against the golden events of pilot case 01.
     Run it with the candidate's OUTPUT as the source document:
       java -cp Saxon-HE-9.9.1-8.jar net.sf.saxon.Transform -s:candidate-output.xml -xsl:check-golden.xslt
     Events are found by their normalised event time, not by position, so a missing or reordered event
     cannot make another event pass. Times are compared as xs:dateTime values, so "Z" and "+00:00" are equal. -->
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:e="event-logging:3"
                exclude-result-prefixes="xs e">

  <xsl:output method="text"/>

  <xsl:function name="e:ev" as="element(e:Event)*">
    <xsl:param name="doc" as="document-node()"/>
    <xsl:param name="utc" as="xs:string"/>
    <xsl:sequence select="$doc//e:Event[e:EventTime/e:TimeCreated castable as xs:dateTime
                          and xs:dateTime(e:EventTime/e:TimeCreated) eq xs:dateTime($utc)]"/>
  </xsl:function>

  <xsl:function name="e:check">
    <xsl:param name="id" as="xs:string"/>
    <xsl:param name="what" as="xs:string"/>
    <xsl:param name="ok" as="xs:boolean"/>
    <xsl:value-of select="concat(if ($ok) then 'PASS ' else 'FAIL ', $id, '  ', $what, '&#10;')"/>
  </xsl:function>

  <xsl:template match="/">
    <xsl:variable name="d" select="."/>
    <xsl:variable name="all" select="//e:Event"/>

    <!-- G1: the swapped-address case. Every structural check passes a swap; these do not. -->
    <xsl:variable name="g1" select="e:ev($d, '2026-11-04T10:00:01Z')"/>
    <xsl:sequence select="e:check('G1.1', 'exactly one event at 10:00:01', count($g1) = 1)"/>
    <xsl:sequence select="e:check('G1.2', 'DROP is recorded as Network/Deny', exists($g1/e:EventDetail/e:Network/e:Deny))"/>
    <xsl:sequence select="e:check('G1.3', 'source address is 10.0.0.5', $g1//e:Source/e:Device/e:IPAddress = '10.0.0.5' and not($g1//e:Destination/e:Device/e:IPAddress = '10.0.0.5'))"/>
    <xsl:sequence select="e:check('G1.4', 'destination address is 203.0.113.9', $g1//e:Destination/e:Device/e:IPAddress = '203.0.113.9' and not($g1//e:Source/e:Device/e:IPAddress = '203.0.113.9'))"/>
    <xsl:sequence select="e:check('G1.5', 'source port 51514, destination port 443', $g1//e:Source//e:Port = 51514 and $g1//e:Destination//e:Port = 443)"/>
    <xsl:sequence select="e:check('G1.6', 'protocol is TCP', $g1//e:TransportProtocol = 'TCP')"/>
    <xsl:sequence select="e:check('G1.7', 'logging device is fw01 (as HostName or Name)', ($g1/e:EventSource/e:Device/e:HostName, $g1/e:EventSource/e:Device/e:Name) = 'fw01')"/>

    <!-- G2: IPv6 and ACCEPT -->
    <xsl:variable name="g2" select="e:ev($d, '2026-11-04T10:00:02Z')"/>
    <xsl:sequence select="e:check('G2.1', 'exactly one event at 10:00:02', count($g2) = 1)"/>
    <xsl:sequence select="e:check('G2.2', 'ACCEPT is recorded as Network/Permit', exists($g2/e:EventDetail/e:Network/e:Permit))"/>
    <xsl:sequence select="e:check('G2.3', 'IPv6 source and destination in the right places',
        $g2//e:Source/e:Device/e:IPAddress = '2001:db8::5' and $g2//e:Destination/e:Device/e:IPAddress = '2001:db8::9')"/>
    <xsl:sequence select="e:check('G2.4', 'UDP, ports 5353 and 53', $g2//e:TransportProtocol = 'UDP' and $g2//e:Source//e:Port = 5353 and $g2//e:Destination//e:Port = 53)"/>

    <!-- G3: dst=- means no destination -->
    <xsl:variable name="g3" select="e:ev($d, '2026-11-04T10:00:03Z')"/>
    <xsl:sequence select="e:check('G3.1', 'the record is kept (source is known)', count($g3) = 1 and $g3//e:Source/e:Device/e:IPAddress = '10.0.0.7')"/>
    <xsl:sequence select="e:check('G3.2', 'the missing-value marker is not output as an address', not($g3//e:IPAddress = '-') and not($g3//e:Destination/e:Device/e:IPAddress))"/>

    <!-- G4: ICMP has no ports -->
    <xsl:variable name="g4" select="e:ev($d, '2026-11-04T10:00:04Z')"/>
    <xsl:sequence select="e:check('G4.1', 'ICMP is recorded, with no Port element and no dash port', count($g4) = 1 and $g4//e:TransportProtocol = 'ICMP' and not($g4//e:Port))"/>

    <!-- G5: offset timestamp, protocol outside the enumeration -->
    <xsl:variable name="g5" select="e:ev($d, '2026-11-04T10:00:05Z')"/>
    <xsl:sequence select="e:check('G5.1', '11:00:05+01:00 is 10:00:05 UTC', count($g5) = 1)"/>
    <xsl:sequence select="e:check('G5.2', 'GRE is recorded as Other', $g5//e:TransportProtocol = 'Other')"/>

    <!-- Negative goldens: what must NOT be produced -->
    <xsl:sequence select="e:check('N1', 'the comment line produced no event', not($all//text()[contains(., 'started')]))"/>
    <xsl:sequence select="e:check('N2', 'the invalid address 10.0.0.999 was not output', not($all//text()[contains(., '10.0.0.999')]) and count(e:ev($d, '2026-11-04T10:00:06Z')) = 0)"/>
    <xsl:sequence select="e:check('N3', 'the garbled line produced no event', not($all//text()[contains(., '~~')]) and count(e:ev($d, '2026-11-04T10:00:07Z')) = 0)"/>
    <xsl:sequence select="e:check('N4', 'exactly 5 events were produced', count($all) = 5)"/>

    <!-- Consistency -->
    <xsl:sequence select="e:check('C1', 'every event has a non-empty TypeId', every $x in $all satisfies normalize-space($x/e:EventDetail/e:TypeId) != '')"/>
    <xsl:sequence select="e:check('C2', 'the same action always gets the same TypeId',
        count(distinct-values($all[e:EventDetail/e:Network/e:Deny]/e:EventDetail/e:TypeId)) le 1)"/>
  </xsl:template>
</xsl:stylesheet>
