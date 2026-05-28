<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
        xmlns="reference-data:2"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
        version="2.0">

    <xsl:template match="records">
      <referenceData version="2.0.1">
         <xsl:apply-templates select="record"/>
      </referenceData>
   </xsl:template>

   <xsl:template match="record">
      <reference>
         <map>Events</map>
         <key><xsl:value-of select="concat(data[@name='timestamp']/@value, '|', data[@name='thing-idref']/@value)"/></key>
         <value>
            <Event>
                <Type><xsl:value-of select="data[@name='type']/@value"/></Type>
                <Timestamp><xsl:value-of select="data[@name='timestamp']/@value"/></Timestamp>
                <User><xsl:value-of select="data[@name='thing-idref']/@value"/></User>
                <Location><xsl:value-of select="data[@name='location-idref']/@value"/></Location>
                <Status>
                    <xsl:choose>
                        <xsl:when test="data[@name='status']/@value">
                            <xsl:value-of select="data[@name='status']/@value"/>
                        </xsl:when>
                        <xsl:otherwise>OK</xsl:otherwise>
                    </xsl:choose>
                </Status>
                <Message><xsl:value-of select="data[@name='message']/@value"/></Message>
            </Event>
         </value>
      </reference>
   </xsl:template>

</xsl:stylesheet>
