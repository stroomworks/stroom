/*
 * This file is generated by jOOQ.
 */
package stroom.security.impl.db.jooq;


import org.jooq.Index;
import org.jooq.OrderField;
import org.jooq.impl.Internal;
import stroom.security.impl.db.jooq.tables.AppPermission;
import stroom.security.impl.db.jooq.tables.DocPermission;
import stroom.security.impl.db.jooq.tables.StroomUser;
import stroom.security.impl.db.jooq.tables.StroomUserGroup;

import javax.annotation.Generated;


/**
 * A class modelling indexes of tables of the <code>stroom</code> schema.
 */
@Generated(
    value = {
        "http://www.jooq.org",
        "jOOQ version:3.11.9"
    },
    comments = "This class is generated by jOOQ"
)
@SuppressWarnings({ "all", "unchecked", "rawtypes" })
public class Indexes {

    // -------------------------------------------------------------------------
    // INDEX definitions
    // -------------------------------------------------------------------------

    public static final Index APP_PERMISSION_PRIMARY = Indexes0.APP_PERMISSION_PRIMARY;
    public static final Index APP_PERMISSION_USER_UUID = Indexes0.APP_PERMISSION_USER_UUID;
    public static final Index DOC_PERMISSION_PRIMARY = Indexes0.DOC_PERMISSION_PRIMARY;
    public static final Index DOC_PERMISSION_USER_UUID = Indexes0.DOC_PERMISSION_USER_UUID;
    public static final Index STROOM_USER_NAME = Indexes0.STROOM_USER_NAME;
    public static final Index STROOM_USER_PRIMARY = Indexes0.STROOM_USER_PRIMARY;
    public static final Index STROOM_USER_STROOM_USER_UUID_INDEX = Indexes0.STROOM_USER_STROOM_USER_UUID_INDEX;
    public static final Index STROOM_USER_GROUP_GROUP_UUID = Indexes0.STROOM_USER_GROUP_GROUP_UUID;
    public static final Index STROOM_USER_GROUP_PRIMARY = Indexes0.STROOM_USER_GROUP_PRIMARY;
    public static final Index STROOM_USER_GROUP_USER_UUID = Indexes0.STROOM_USER_GROUP_USER_UUID;

    // -------------------------------------------------------------------------
    // [#1459] distribute members to avoid static initialisers > 64kb
    // -------------------------------------------------------------------------

    private static class Indexes0 {
        public static Index APP_PERMISSION_PRIMARY = Internal.createIndex("PRIMARY", AppPermission.APP_PERMISSION, new OrderField[] { AppPermission.APP_PERMISSION.ID }, true);
        public static Index APP_PERMISSION_USER_UUID = Internal.createIndex("user_uuid", AppPermission.APP_PERMISSION, new OrderField[] { AppPermission.APP_PERMISSION.USER_UUID }, false);
        public static Index DOC_PERMISSION_PRIMARY = Internal.createIndex("PRIMARY", DocPermission.DOC_PERMISSION, new OrderField[] { DocPermission.DOC_PERMISSION.ID }, true);
        public static Index DOC_PERMISSION_USER_UUID = Internal.createIndex("user_uuid", DocPermission.DOC_PERMISSION, new OrderField[] { DocPermission.DOC_PERMISSION.USER_UUID }, false);
        public static Index STROOM_USER_NAME = Internal.createIndex("name", StroomUser.STROOM_USER, new OrderField[] { StroomUser.STROOM_USER.NAME, StroomUser.STROOM_USER.IS_GROUP }, true);
        public static Index STROOM_USER_PRIMARY = Internal.createIndex("PRIMARY", StroomUser.STROOM_USER, new OrderField[] { StroomUser.STROOM_USER.ID }, true);
        public static Index STROOM_USER_STROOM_USER_UUID_INDEX = Internal.createIndex("stroom_user_uuid_index", StroomUser.STROOM_USER, new OrderField[] { StroomUser.STROOM_USER.UUID }, true);
        public static Index STROOM_USER_GROUP_GROUP_UUID = Internal.createIndex("group_uuid", StroomUserGroup.STROOM_USER_GROUP, new OrderField[] { StroomUserGroup.STROOM_USER_GROUP.GROUP_UUID }, false);
        public static Index STROOM_USER_GROUP_PRIMARY = Internal.createIndex("PRIMARY", StroomUserGroup.STROOM_USER_GROUP, new OrderField[] { StroomUserGroup.STROOM_USER_GROUP.ID }, true);
        public static Index STROOM_USER_GROUP_USER_UUID = Internal.createIndex("user_uuid", StroomUserGroup.STROOM_USER_GROUP, new OrderField[] { StroomUserGroup.STROOM_USER_GROUP.USER_UUID }, false);
    }
}