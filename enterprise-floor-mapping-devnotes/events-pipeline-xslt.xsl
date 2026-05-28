<?xml version="1.0" encoding="UTF-8" ?>
<!--
  ~ Copyright 2016-2026 Crown Copyright
  ~
  ~ Licensed under the Apache License, Version 2.0 (the "License");
  ~ you may not use this file except in compliance with the License.
  ~ You may obtain a copy of the License at
  ~
  ~     http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing, software
  ~ distributed under the License is distributed on an "AS IS" BASIS,
  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  ~ See the License for the specific language governing permissions and
  ~ limitations under the License.
  -->

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
            <xsl:variable name="type"           select="records:data[@name='type']/@value"/>
            <xsl:variable name="thing-idref"    select="records:data[@name='thing-idref']/@value"/>
            <xsl:variable name="location-idref" select="records:data[@name='location-idref']/@value"/>
            <xsl:variable name="timestamp"      select="records:data[@name='timestamp']/@value"/>

            <xsl:if test="$thing-idref">
                <xsl:variable name="userData" select="stroom:lookup('UserMap', $thing-idref, $timestamp)"/>

                <!-- If we found reference data, inject it as new data elements -->
                <xsl:if test="$userData">
                    <!-- Iterate over the children of the returned <fact> element -->
                    <xsl:for-each select="$userData/fact/*">
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


            <xsl:if test="$location-idref">
                <!-- Determine the map name based on the type -->
                <xsl:variable name="mapName">
                    <xsl:choose>
                        <xsl:when test="$type = 'badge-scan'">GateMap</xsl:when>
                        <xsl:when test="$type = 'gate-event'">GateMap</xsl:when>
                        <xsl:when test="$type = 'network-device-event'">DeviceMap</xsl:when>
                        <xsl:when test="$type = 'printer-event'">DeviceMap</xsl:when>
                        <xsl:otherwise>Unknown-Type</xsl:otherwise>
                    </xsl:choose>
                </xsl:variable>

                <!-- Perform the lookup -->
                <!-- Note: stroom:lookup returns the XML fragment stored in the <value> element -->
                <xsl:variable name="refData" select="stroom:lookup($mapName, $thing-idref, $timestamp)"/>

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




<xsl:copy>
<!-- Copy existing data elements -->
<xsl:apply-templates select="@*|node()"/>

<!-- Enrichment Logic -->
<xsl:variable name="type"           select="records:data[@name='type']/@value"/>
<xsl:variable name="thing-idref"    select="records:data[@name='thing-idref']/@value"/>
<xsl:variable name="location-idref" select="records:data[@name='location-idref']/@value"/>
<xsl:variable name="timestamp"      select="records:data[@name='timestamp']/@value"/>

<xsl:if test="$thing-idref">
    <xsl:variable name="userData" select="stroom:lookup('UserMap', $thing-idref, $timestamp)"/>

    <xsl:sequence select="$userData"/>

    <!-- If we found reference data, inject it as new data elements -->
    <xsl:if test="$userData">
        <!-- Iterate over the children of the returned <fact> element -->
        <xsl:for-each select="$userData/fact/*">
            <xsl:copy-of select="name"/>
            <xsl:copy-of select="icon"/>

            <data xmlns="records:2">
                <xsl:attribute name="name">
                    <xsl:value-of select="."/>
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
