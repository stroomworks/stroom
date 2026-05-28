<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xmlns="reference-data:2"
    version="2.0">

    <xsl:template match="records">
        <referenceData xmlns="reference-data:2">
            <xsl:apply-templates select="record"/>
        </referenceData>
    </xsl:template>

    <xsl:template match="record">
        <xsl:variable name="type" select="data[@name='type']/@value"/>
        <xsl:variable name="id" select="data[@name='id']/@value"/>

        <xsl:if test="$id">
            <reference>
                <map>
                    <xsl:choose>
                        <xsl:when test="$type = 'person'">UserMap</xsl:when>
                        <xsl:when test="$type = 'gate'">GateMap</xsl:when>
                        <xsl:when test="$type = 'network-device'">DeviceMap</xsl:when>
                        <xsl:when test="$type = 'background-image'">BackgroundMap</xsl:when>
                        <xsl:otherwise>
                            <xsl:value-of select="concat(upper-case(substring($type, 1, 1)), substring($type, 2), 'Map')"/>
                        </xsl:otherwise>
                    </xsl:choose>
                </map>
                <key><xsl:value-of select="$id"/></key>
                <value>
                    <fact>
                        <!-- Copy all data elements as child elements of <fact>,
                             excluding the structural ones like type and id -->
                        <xsl:for-each select="data[not(@name='type' or @name='id' or @name='valid-from')]">
                            <xsl:element name="{@name}">
                                <xsl:value-of select="@value"/>
                            </xsl:element>
                        </xsl:for-each>
                    </fact>
                </value>
            </reference>
        </xsl:if>
    </xsl:template>

</xsl:stylesheet>
