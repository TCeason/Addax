/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *   http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing,
 *  * software distributed under the License is distributed on an
 *  * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  * KIND, either express or implied.  See the License for the
 *  * specific language governing permissions and limitations
 *  * under the License.
 *
 */

package com.wgzhao.addax.rdbms.reader.util;

import com.wgzhao.addax.common.base.Constant;
import com.wgzhao.addax.common.base.Key;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.util.Configuration;
import com.wgzhao.addax.common.util.EncryptUtil;
import com.wgzhao.addax.common.util.ListUtil;
import com.wgzhao.addax.rdbms.util.DBUtil;
import com.wgzhao.addax.rdbms.util.DBUtilErrorCode;
import com.wgzhao.addax.rdbms.util.DataBaseType;
import com.wgzhao.addax.rdbms.util.TableExpandUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class OriginalConfPretreatmentUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(OriginalConfPretreatmentUtil.class);

    public static DataBaseType dataBaseType;

    private OriginalConfPretreatmentUtil() {}

    public static void doPretreatment(Configuration originalConfig)
    {
        // 检查 username 配置（必填）
        originalConfig.getNecessaryValue(Key.USERNAME, DBUtilErrorCode.REQUIRED_VALUE);
        /*
         * 有些数据库没有密码，因此密码不再作为必选项
         */
        if (originalConfig.getString(Key.PASSWORD) == null) {
            originalConfig.set(Key.PASSWORD, "");
        }
        else if (originalConfig.getString(Key.PASSWORD).startsWith(Constant.ENC_PASSWORD_PREFIX)) {
            // encrypted password, need to decrypt
            String pass = originalConfig.getString(Key.PASSWORD);
            String decryptPassword = EncryptUtil.decrypt(pass.substring(6, pass.length() - 1));
            originalConfig.set(Key.PASSWORD, decryptPassword);
        }
        dealWhere(originalConfig);

        simplifyConf(originalConfig);
    }

    public static void dealWhere(Configuration originalConfig)
    {
        String where = originalConfig.getString(Key.WHERE, null);
        if (StringUtils.isNotBlank(where)) {
            String whereImprove = where.trim();
            if (whereImprove.endsWith(";") || whereImprove.endsWith("；")) {
                whereImprove = whereImprove.substring(0, whereImprove.length() - 1);
            }
            originalConfig.set(Key.WHERE, whereImprove);
        }
    }

    /**
     * 对配置进行初步处理：
     * <ol>
     * <li>处理同一个数据库配置了多个jdbcUrl的情况</li>
     * <li>识别并标记是采用querySql 模式还是 table 模式</li>
     * <li>对 table 模式，确定分表个数，并处理 column 转 *事项</li>
     * </ol>
     *
     * @param originalConfig configuration
     */
    private static void simplifyConf(Configuration originalConfig)
    {
        boolean isTableMode = recognizeTableOrQuerySqlMode(originalConfig);
        originalConfig.set(Key.IS_TABLE_MODE, isTableMode);

        dealJdbcAndTable(originalConfig);

        dealColumnConf(originalConfig);
    }

    private static void dealJdbcAndTable(Configuration originalConfig)
    {
        String username = originalConfig.getString(Key.USERNAME);
        String password = originalConfig.getString(Key.PASSWORD);
        boolean isTableMode = originalConfig.getBool(Key.IS_TABLE_MODE);
        boolean isPreCheck = originalConfig.getBool(Key.DRY_RUN, false);

        List<Object> conns = originalConfig.getList(Key.CONNECTION, Object.class);
        List<String> preSql = originalConfig.getList(Key.PRE_SQL, String.class);

        int tableNum = 0;

        for (int i = 0, len = conns.size(); i < len; i++) {
            Configuration connConf = Configuration.from(conns.get(i).toString());
            // 是否配置的定制的驱动名称
            String driverClass = connConf.getString(Key.JDBC_DRIVER, null);
            if (driverClass != null && !driverClass.isEmpty()) {
                LOG.warn("use specified driver class: {}", driverClass);
                dataBaseType.setDriverClassName(driverClass);
            }
            connConf.getNecessaryValue(Key.JDBC_URL, DBUtilErrorCode.REQUIRED_VALUE);

            List<String> jdbcUrls = connConf.getList(Key.JDBC_URL, String.class);

            String jdbcUrl;
            if (isPreCheck) {
                jdbcUrl = DBUtil.chooseJdbcUrlWithoutRetry(dataBaseType, jdbcUrls, username, password, preSql);
            }
            else {
                jdbcUrl = DBUtil.chooseJdbcUrl(dataBaseType, jdbcUrls, username, password, preSql);
            }

            jdbcUrl = dataBaseType.appendJDBCSuffixForReader(jdbcUrl);

            // 回写到connection[i].jdbcUrl
            originalConfig.set(String.format("%s[%d].%s", Key.CONNECTION, i, Key.JDBC_URL), jdbcUrl);

            LOG.info("Available jdbcUrl [{}].", jdbcUrl);

            if (isTableMode) {
                // table 方式
                // 对每一个connection 上配置的table 项进行解析(已对表名称进行了 ` 处理的)
                List<String> tables = connConf.getList(Key.TABLE, String.class);

                List<String> expandedTables = TableExpandUtil.expandTableConf(dataBaseType, tables);

                if (expandedTables.isEmpty()) {
                    throw AddaxException.asAddaxException(
                            DBUtilErrorCode.ILLEGAL_VALUE, String.format("Failed to obtain the table [%s].", StringUtils.join(tables, ",")));
                }

                tableNum += expandedTables.size();

                originalConfig.set(String.format("%s[%d].%s", Key.CONNECTION, i, Key.TABLE), expandedTables);
            }
        }

        originalConfig.set(Constant.TABLE_NUMBER_MARK, tableNum);
    }

    private static void dealColumnConf(Configuration originalConfig)
    {
        boolean isTableMode = originalConfig.getBool(Key.IS_TABLE_MODE);

        List<String> userConfiguredColumns = originalConfig.getList(Key.COLUMN, String.class);

        if (isTableMode) {
            if (null == userConfiguredColumns
                    || userConfiguredColumns.isEmpty()) {
                throw AddaxException.asAddaxException(DBUtilErrorCode.REQUIRED_VALUE, "The item column is required.");
            }
            else {
                String splitPk = originalConfig.getString(Key.SPLIT_PK, null);

                if (1 == userConfiguredColumns.size()
                        && "*".equals(userConfiguredColumns.get(0))) {
                    LOG.warn("There are some risks in the column configuration. Because you did not configure the columns " +
                            "to read the database table, changes in the number and types of fields in your table may affect " +
                            "the correctness of the task or even cause errors.");
                    // 回填其值，需要以 String 的方式转交后续处理
                    originalConfig.set(Key.COLUMN, "*");
                }
                else {
                    String jdbcUrl = originalConfig.getString(String.format("%s[0].%s", Key.CONNECTION, Key.JDBC_URL));

                    String username = originalConfig.getString(Key.USERNAME);
                    String password = originalConfig.getString(Key.PASSWORD);

                    String tableName = originalConfig.getString(String.format("%s[0].%s[0]", Key.CONNECTION, Key.TABLE));

                    List<String> allColumns = DBUtil.getTableColumns(dataBaseType, jdbcUrl, username, password, tableName);
                    LOG.info("The table [{}] has columns [{}].", tableName, StringUtils.join(allColumns, ","));
                    // warn:注意mysql表名区分大小写
                    allColumns = ListUtil.valueToLowerCase(allColumns);
                    List<String> quotedColumns = new ArrayList<>();

                    for (String column : userConfiguredColumns) {
                        if ("*".equals(column)) {
                            throw AddaxException.asAddaxException(DBUtilErrorCode.ILLEGAL_VALUE,
                                    "The item column your configured is invalid, because it includes multiply asterisk('*').");
                        }

                        quotedColumns.add(dataBaseType.quoteColumnName(column));
                    }

                    originalConfig.set(Key.COLUMN_LIST, quotedColumns);
                    originalConfig.set(Key.COLUMN, StringUtils.join(quotedColumns, ","));
                    if (StringUtils.isNotBlank(splitPk) && !allColumns.contains(splitPk.toLowerCase())) {
                        throw AddaxException.asAddaxException(DBUtilErrorCode.ILLEGAL_SPLIT_PK,
                                String.format("The table [%s] has not the primary key [%s].", tableName, splitPk));
                    }
                }
            }
        }
        else {
            // querySql模式，不希望配制 column，那样是混淆不清晰的
            if (null != userConfiguredColumns && !userConfiguredColumns.isEmpty()) {
                LOG.warn("You configured both column and querySql, querySql will be preferred.");
                originalConfig.remove(Key.COLUMN);
            }

            // querySql模式，不希望配制 where，那样是混淆不清晰的
            String where = originalConfig.getString(Key.WHERE, null);
            if (StringUtils.isNotBlank(where)) {
                LOG.warn("You configured both querySql and where. the where will be ignored.");
                originalConfig.remove(Key.WHERE);
            }

            // querySql模式，不希望配制 splitPk，那样是混淆不清晰的
            String splitPk = originalConfig.getString(Key.SPLIT_PK, null);
            if (StringUtils.isNotBlank(splitPk)) {
                LOG.warn("You configured both querySql and splitPk. the splitPk will be ignored.");
                originalConfig.remove(Key.SPLIT_PK);
            }
        }
    }

    private static boolean recognizeTableOrQuerySqlMode(
            Configuration originalConfig)
    {
        List<Object> conns = originalConfig.getList(Key.CONNECTION, Object.class);

        List<Boolean> tableModeFlags = new ArrayList<>();
        List<Boolean> querySqlModeFlags = new ArrayList<>();

        String table;
        String querySql;

        boolean isTableMode;
        boolean isQuerySqlMode;
        for (Object conn : conns) {
            Configuration connConf = Configuration.from(conn.toString());
            table = connConf.getString(Key.TABLE, null);
            querySql = connConf.getString(Key.QUERY_SQL, null);

            isTableMode = StringUtils.isNotBlank(table);
            tableModeFlags.add(isTableMode);

            isQuerySqlMode = StringUtils.isNotBlank(querySql);
            querySqlModeFlags.add(isQuerySqlMode);

            if (!isTableMode && !isQuerySqlMode) {
                // table 和 querySql 二者均未配置
                throw AddaxException.asAddaxException(
                        DBUtilErrorCode.TABLE_QUERY_SQL_MISSING, "You must configure either table or querySql.");
            }
            else if (isTableMode && isQuerySqlMode) {
                // table 和 querySql 二者均配置
                throw AddaxException.asAddaxException(DBUtilErrorCode.TABLE_QUERY_SQL_MIXED,
                        "You ca not configure both table and querySql at the same time.");
            }
        }

        // 混合配制 table 和 querySql
        if (!ListUtil.checkIfValueSame(tableModeFlags) || !ListUtil.checkIfValueSame(querySqlModeFlags)) {
            throw AddaxException.asAddaxException(DBUtilErrorCode.TABLE_QUERY_SQL_MIXED,
                    "You ca not configure both table and querySql at the same time.");
        }

        return tableModeFlags.get(0);
    }
}
