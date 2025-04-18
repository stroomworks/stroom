package stroom.meta.impl;

import stroom.docref.DocRef;
import stroom.meta.shared.Meta;
import stroom.meta.shared.MetaFields;
import stroom.meta.shared.Status;
import stroom.util.date.DateUtil;
import stroom.util.shared.NullSafe;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

class StreamAttributeMapUtil {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamAttributeMapUtil.class);

    private StreamAttributeMapUtil() {
        // Utility.
    }

    /**
     * Turns a stream attribute map object into a generic map of attributes for use by an expression filter.
     */
    static Map<String, Object> createAttributeMap(final Meta meta, final Map<String, String> attributeMap) {
        final Map<String, Object> map = new HashMap<>();

        if (meta != null) {
            map.put(MetaFields.ID.getFldName(), meta.getId());
            map.put(MetaFields.CREATE_TIME.getFldName(), meta.getCreateMs());
            map.put(MetaFields.EFFECTIVE_TIME.getFldName(), meta.getEffectiveMs());
            map.put(MetaFields.STATUS_TIME.getFldName(), meta.getStatusMs());
            map.put(MetaFields.STATUS.getFldName(), NullSafe.get(meta.getStatus(), Status::getDisplayValue));
            if (meta.getParentMetaId() != null) {
                map.put(MetaFields.PARENT_ID.getFldName(), meta.getParentMetaId());
            }
            if (meta.getTypeName() != null) {
                map.put(MetaFields.TYPE.getFldName(), meta.getTypeName());
            }
            final String feedName = meta.getFeedName();
            if (feedName != null) {
                map.put(MetaFields.FEED.getFldName(), feedName);
            }
            final String pipelineUuid = meta.getPipelineUuid();
            if (pipelineUuid != null) {
                map.put(MetaFields.PIPELINE.getFldName(), new DocRef("Pipeline", pipelineUuid));
            }
//            if (streamProcessor != null) {
//                final String pipelineUuid = streamProcessor.getPipelineUuid();
//                if (pipelineUuid != null) {
//                    attributeMap.put(StreamDataSource.PIPELINE, pipelineUuid);
//                }
//            }
        }

        MetaFields.getExtendedFields().forEach(field -> {
            final String value = attributeMap.get(field.getFldName());
            if (value != null) {
                try {
                    switch (field.getFldType()) {
                        case TEXT:
                            map.put(field.getFldName(), value);
                            break;
                        case DATE:
                            map.put(field.getFldName(), DateUtil.parseNormalDateTimeString(value));
                            break;
                        case DOC_REF:
                            attributeMap.put(field.getFldName(), value);
                            break;
                        case ID:
                        case LONG:
                            map.put(field.getFldName(), Long.valueOf(value));
                            break;
                    }
                } catch (final RuntimeException e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        });
        return map;
    }
}
