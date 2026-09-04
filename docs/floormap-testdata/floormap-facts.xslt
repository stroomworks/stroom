<?xml version="1.0" encoding="UTF-8" ?>
<!--
  Facts CSV -> reference-data:2, for a SQL Temporal Store.

  Input is records:2 from the shared CSV_WITH_HEADER data splitter: one <record> per line, with
  <data name="..." value="..."/> children named by the CSV header.

  Output is the shape SqlStoreFilter consumes. <map> is the SQL Temporal Store document's NAME -
  still resolved by name (UpdatableTemporalStoreProvider), even though rows are stored against the
  document UUID since the F1 re-key.

  The interesting work is here rather than in the CSV. The facts value is a JSON object whose keys
  are the paths in the floor map's value schema (FloorMapFieldMapping.initialValueSchema), so the
  CSV stays flat and quote-free and this assembles it. Members are collected as a sequence and
  joined, rather than concatenated with separators inline: an absent leading field would otherwise
  produce {,"name":...}. Empty columns are omitted rather than emitted as null, because the parser
  distinguishes an absent path from a null value.
-->
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns="reference-data:2"
    xmlns:records="records:2"
    xmlns:xs="http://www.w3.org/2001/XMLSchema"
    xmlns:fm="urn:floormap-testdata"
    exclude-result-prefixes="records xs fm"
    version="2.0">

  <xsl:output method="xml" indent="yes"/>

  <!-- Quote and escape a JSON string. The generated data needs neither; a hand-edited row might. -->
  <xsl:function name="fm:str" as="xs:string">
    <xsl:param name="s" as="xs:string"/>
    <xsl:sequence select="concat('&quot;',
                                 replace(replace($s, '\\', '\\\\'), '&quot;', '\\&quot;'),
                                 '&quot;')"/>
  </xsl:function>

  <!-- A space-separated numeric column becomes a JSON array: "1 0 0 1 0 0" -> [1,0,0,1,0,0]. -->
  <xsl:function name="fm:arr" as="xs:string">
    <xsl:param name="s" as="xs:string"/>
    <xsl:sequence select="concat('[', replace(normalize-space($s), ' ', ','), ']')"/>
  </xsl:function>

  <xsl:template match="records:records">
    <referenceData>
      <xsl:apply-templates select="records:record"/>
    </referenceData>
  </xsl:template>

  <xsl:template match="records:record">
    <xsl:variable name="type" select="string(records:data[@name='type']/@value)"/>
    <xsl:variable name="name" select="string(records:data[@name='name']/@value)"/>
    <xsl:variable name="x" select="string(records:data[@name='x']/@value)"/>
    <xsl:variable name="y" select="string(records:data[@name='y']/@value)"/>
    <xsl:variable name="img" select="string(records:data[@name='img']/@value)"/>
    <xsl:variable name="matrix" select="string(records:data[@name='matrix']/@value)"/>
    <xsl:variable name="geometry" select="string(records:data[@name='geometry']/@value)"/>
    <xsl:variable name="fill" select="string(records:data[@name='fill']/@value)"/>
    <xsl:variable name="opacity" select="string(records:data[@name='opacity']/@value)"/>

    <xsl:variable name="members" as="xs:string*">
      <xsl:if test="$type != ''">
        <xsl:sequence select="concat('&quot;type&quot;:', fm:str($type))"/>
      </xsl:if>
      <xsl:if test="$name != ''">
        <xsl:sequence select="concat('&quot;name&quot;:', fm:str($name))"/>
      </xsl:if>
      <!-- POSITION is read as an array; the table parser strips [] and splits on commas. -->
      <xsl:if test="$x != '' and $y != ''">
        <xsl:sequence select="concat('&quot;coords&quot;:[', $x, ',', $y, ']')"/>
      </xsl:if>
      <xsl:if test="$img != ''">
        <xsl:sequence select="concat('&quot;img&quot;:', fm:str($img))"/>
      </xsl:if>
      <!--
        WORLD_TO_MAP places every fact, backgrounds included. Six elements a b c d e f, given
        space-separated in the CSV so the field needs no quoting.
      -->
      <xsl:if test="$matrix != ''">
        <xsl:sequence select="concat('&quot;tm-world-to-map&quot;:', fm:arr($matrix))"/>
      </xsl:if>
      <!-- GEOMETRY is a flat vertex array x0 y0 x1 y1 ... in the fact's local frame. -->
      <xsl:if test="$geometry != ''">
        <xsl:sequence select="concat('&quot;geometry&quot;:', fm:arr($geometry))"/>
      </xsl:if>
      <xsl:if test="$fill != ''">
        <xsl:sequence select="concat('&quot;fill&quot;:', fm:str($fill))"/>
      </xsl:if>
      <xsl:if test="$opacity != ''">
        <xsl:sequence select="concat('&quot;opacity&quot;:', $opacity)"/>
      </xsl:if>
    </xsl:variable>

    <!--
      temporal-state, not the generic reference element. Both reach the same code, but this one
      asserts the store's state type and says so if it is wrong; reference dispatches on whatever
      the store happens to be and fails later, less clearly.
    -->
    <temporal-state>
      <map><xsl:value-of select="records:data[@name='map']/@value"/></map>
      <key><xsl:value-of select="records:data[@name='key']/@value"/></key>
      <!--
        An explicit per-row time. Without it the whole stream lands at the stream's effective time,
        which would collapse a deliberate "this desk moved at 10:15" into the load time.
      -->
      <time><xsl:value-of select="records:data[@name='time']/@value"/></time>
      <value><xsl:value-of select="concat('{', string-join($members, ','), '}')"/></value>
    </temporal-state>
  </xsl:template>

</xsl:stylesheet>
