<?xml version="1.0" encoding="UTF-8" ?>
<xsl:stylesheet
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:stroom="stroom"
    xmlns:records="records:2"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    version="2.0"
    exclude-result-prefixes="stroom">

    <!-- Standard identity template to copy everything by default -->
    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>

    <!-- Match the record element in the records:2 namespace -->
    <xsl:template match="records:record">
        <xsl:copy>
            <!-- Copy existing data elements -->
            <xsl:apply-templates select="@*|node()"/>

            <!-- Enrichment Logic -->
            <xsl:variable name="type" select="records:data[@name='type']/@value"/>
            <xsl:variable name="id" select="records:data[@name='id']/@value"/>

            <xsl:if test="$id">
                <!-- Determine the map name based on the type -->
                <xsl:variable name="mapName">
                    <xsl:choose>
                        <xsl:when test="$type = 'person'">UserMap</xsl:when>
                        <xsl:when test="$type = 'gate'">GateMap</xsl:when>
                        <xsl:when test="$type = 'network-device'">DeviceMap</xsl:when>
                        <xsl:when test="$type = 'background-image'">BackgroundMap</xsl:when>
                        <xsl:otherwise>OtherMap</xsl:otherwise>
                    </xsl:choose>
                </xsl:variable>

                <!-- Perform the lookup -->
                <!-- Note: stroom:lookup returns the XML fragment stored in the <value> element -->
                <xsl:variable name="refData" select="stroom:lookup($mapName, $id)"/>

                <!-- If we found reference data, inject it as new data elements -->
                <xsl:if test="$refData">
                    <!-- Iterate over the children of the returned <fact> element -->
                    <xsl:for-each select="$refData/fact/*">
                        <data xmlns="records:2">
                            <xsl:attribute name="name">
                                <xsl:value-of select="concat('ref-', local-name())"/>
                            </xsl:attribute>
                            <xsl:attribute name="value">
                                <xsl:value-of select="."/>
                            </xsl:attribute>
                        </data>
                    </xsl:for-each>
                </xsl:if>
            </xsl:if>
        </xsl:copy>
    </xsl:template>

</xsl:stylesheet>
