<?xml version="1.0" encoding="UTF-8"?>
<!-- Golden events for pilot case 03. Run with the candidate's OUTPUT as the source (see tools/run-checks.sh).
     Events are found by their UTC event time, compared as xs:dateTime. Port may be under Source or under
     Source/Device; the host may be HostName or Name. -->
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

    <!-- G1: THE REVERSED CONVENTION. The log lists the DESTINATION first. -->
    <xsl:variable name="g1" select="e:ev($d, '2026-11-04T10:00:01Z')"/>
    <xsl:sequence select="e:check('G1.1', 'DENY is Network/Deny', count($g1) = 1 and exists($g1/e:EventDetail/e:Network/e:Deny))"/>
    <xsl:sequence select="e:check('G1.2', 'the SECOND address 10.2.0.5:51514 is the source',
        $g1//e:Source/e:Device/e:IPAddress = '10.2.0.5' and $g1//e:Source//e:Port = 51514
        and not($g1//e:Destination/e:Device/e:IPAddress = '10.2.0.5'))"/>
    <xsl:sequence select="e:check('G1.3', 'the FIRST address 198.51.100.7:443 is the destination',
        $g1//e:Destination/e:Device/e:IPAddress = '198.51.100.7' and $g1//e:Destination//e:Port = 443
        and not($g1//e:Source/e:Device/e:IPAddress = '198.51.100.7'))"/>
    <xsl:sequence select="e:check('G1.4', 'protocol is TCP; logging device is gw03',
        $g1//e:TransportProtocol = 'TCP' and ($g1/e:EventSource/e:Device/e:HostName, $g1/e:EventSource/e:Device/e:Name) = 'gw03')"/>

    <!-- G2: the port heuristic also points the wrong way here: the first address has the ephemeral port 52100 -->
    <xsl:variable name="g2" select="e:ev($d, '2026-11-04T10:00:02Z')"/>
    <xsl:sequence select="e:check('G2.1', 'PERMIT is Network/Permit', count($g2) = 1 and exists($g2/e:EventDetail/e:Network/e:Permit))"/>
    <xsl:sequence select="e:check('G2.2', 'source is 198.51.100.9:3389 (the SECOND address)',
        $g2//e:Source/e:Device/e:IPAddress = '198.51.100.9' and $g2//e:Source//e:Port = 3389)"/>
    <xsl:sequence select="e:check('G2.3', 'destination is 10.2.0.20:52100 (the FIRST address)',
        $g2//e:Destination/e:Device/e:IPAddress = '10.2.0.20' and $g2//e:Destination//e:Port = 52100)"/>

    <!-- G3 ICMP with empty ports -->
    <xsl:variable name="g3" select="e:ev($d, '2026-11-04T10:00:03Z')"/>
    <xsl:sequence select="e:check('G3.1', 'ICMP, no Port; source 198.51.100.11, destination 10.2.0.9',
        count($g3) = 1 and $g3//e:TransportProtocol = 'ICMP' and not($g3//e:Port)
        and $g3//e:Source/e:Device/e:IPAddress = '198.51.100.11' and $g3//e:Destination/e:Device/e:IPAddress = '10.2.0.9')"/>

    <!-- G4 IPv6 -->
    <xsl:variable name="g4" select="e:ev($d, '2026-11-04T10:00:04Z')"/>
    <xsl:sequence select="e:check('G4.1', 'IPv6 source fe80::7, destination 2001:db8::9, UDP',
        count($g4) = 1 and $g4//e:Source/e:Device/e:IPAddress = 'fe80::7' and $g4//e:Destination/e:Device/e:IPAddress = '2001:db8::9'
        and $g4//e:TransportProtocol = 'UDP')"/>

    <!-- G5 TEARDOWN is Close -->
    <xsl:variable name="g5" select="e:ev($d, '2026-11-04T10:00:06Z')"/>
    <xsl:sequence select="e:check('G5.1', 'TEARDOWN is Network/Close, source 10.2.0.5, destination 198.51.100.7',
        count($g5) = 1 and exists($g5/e:EventDetail/e:Network/e:Close)
        and $g5//e:Source/e:Device/e:IPAddress = '10.2.0.5' and $g5//e:Destination/e:Device/e:IPAddress = '198.51.100.7')"/>

    <!-- Negative goldens -->
    <xsl:sequence select="e:check('N1', 'the invalid address 10.2.0.256 was not output, and no event at 10:00:05',
        not($all//text()[contains(., '10.2.0.256')]) and count(e:ev($d, '2026-11-04T10:00:05Z')) = 0)"/>
    <xsl:sequence select="e:check('N2', 'exactly 5 events were produced', count($all) = 5)"/>

    <!-- Consistency -->
    <xsl:sequence select="e:check('C1', 'every event has a non-empty TypeId', every $x in $all satisfies normalize-space($x/e:EventDetail/e:TypeId) != '')"/>
    <xsl:sequence select="e:check('C2', 'the same action always gets the same TypeId',
        count(distinct-values($all[e:EventDetail/e:Network/e:Deny]/e:EventDetail/e:TypeId)) le 1)"/>
  </xsl:template>
</xsl:stylesheet>
