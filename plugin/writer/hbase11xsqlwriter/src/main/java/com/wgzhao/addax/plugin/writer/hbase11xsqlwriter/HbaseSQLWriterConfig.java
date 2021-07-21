/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.plugin.writer.hbase11xsqlwriter;

import com.wgzhao.addax.common.base.HBaseConstant;
import com.wgzhao.addax.common.base.HBaseKey;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.util.Configuration;
import com.google.common.base.Strings;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * HBase SQL writer config
 *
 * @author yanghan.y
 */
public class HbaseSQLWriterConfig
{
    private static final Logger LOG = LoggerFactory.getLogger(HbaseSQLWriterConfig.class);
    private Configuration originalConfig;   // 原始的配置数据

    // 集群配置
    private String connectionString;

    // 表配置
    private String tableName;
    private List<String> columns;           // 目的表的所有列的列名，包括主键和非主键，不包括时间列

    // 其他配置
    private NullModeType nullMode;
    private int batchSize;                  // 一次批量写入多少行
    private boolean truncate;               // 导入开始前是否要清空目的表
    private boolean isThinClient;
    private String namespace;
    private String username;
    private String password;

    // kerberos 配置
    private boolean haveKerberos;
    private String kerberosKeytabFilePath;
    private String kerberosPrincipal;

    /**
     * 禁止直接实例化本类，必须调用{@link #parse}接口来初始化
     */
    private HbaseSQLWriterConfig()
    {
    }

    /**
     * @param jobConf configuration json
     * @return hbase writer class
     */
    public static HbaseSQLWriterConfig parse(Configuration jobConf)
    {
        assert jobConf != null;
        HbaseSQLWriterConfig cfg = new HbaseSQLWriterConfig();
        cfg.originalConfig = jobConf;

        // 1. 解析集群配置
        parseClusterConfig(cfg, jobConf);

        // 2. 解析列配置
        parseTableConfig(cfg, jobConf);

        // 3. 解析其他配置
        cfg.nullMode = NullModeType.getByTypeName(jobConf.getString(HBaseKey.NULL_MODE, HBaseConstant.DEFAULT_NULL_MODE));
        cfg.batchSize = jobConf.getInt(HBaseKey.BATCH_SIZE, HBaseConstant.DEFAULT_BATCH_ROW_COUNT);
        cfg.truncate = jobConf.getBool(HBaseKey.TRUNCATE, HBaseConstant.DEFAULT_TRUNCATE);
        cfg.isThinClient = jobConf.getBool(HBaseKey.THIN_CLIENT, HBaseConstant.DEFAULT_USE_THIN_CLIENT);

        // 4. 解析kerberos 配置
        cfg.haveKerberos = jobConf.getBool(HBaseKey.HAVE_KERBEROS, HBaseConstant.DEFAULT_HAVE_KERBEROS);
        cfg.kerberosPrincipal = jobConf.getString(HBaseKey.KERBEROS_PRINCIPAL, HBaseConstant.DEFAULT_KERBEROS_PRINCIPAL);
        cfg.kerberosKeytabFilePath = jobConf.getString(HBaseKey.KERBEROS_KEYTAB_FILE_PATH, HBaseConstant.DEFAULT_KERBEROS_KEYTAB_FILE_PATH);

        // 4. 打印解析出来的配置
        LOG.debug("HBase SQL writer config parsed: {}", cfg);

        return cfg;
    }

    private static void parseClusterConfig(HbaseSQLWriterConfig cfg, Configuration jobConf)
    {
        // 获取hbase集群的连接信息字符串
        String hbaseCfg = jobConf.getString(HBaseKey.HBASE_CONFIG);
        if (StringUtils.isBlank(hbaseCfg)) {
            // 集群配置必须存在且不为空
            throw AddaxException.asAddaxException(
                    HbaseSQLWriterErrorCode.REQUIRED_VALUE,
                    String.format("%s must be configured with the following:  %s and  %s",
                            HBaseKey.HBASE_CONFIG, HConstants.ZOOKEEPER_QUORUM, HConstants.ZOOKEEPER_ZNODE_PARENT));
        }

        if (jobConf.getBool(HBaseKey.THIN_CLIENT, HBaseConstant.DEFAULT_USE_THIN_CLIENT)) {
            Map<String, String> thinConnectConfig = HbaseSQLHelper.getThinConnectConfig(hbaseCfg);
            String thinConnectStr = thinConnectConfig.get(HBaseKey.HBASE_THIN_CONNECT_URL);
            cfg.namespace = thinConnectConfig.get(HBaseKey.HBASE_THIN_CONNECT_NAMESPACE);
            cfg.username = thinConnectConfig.get(HBaseKey.HBASE_THIN_CONNECT_USERNAME);
            cfg.password = thinConnectConfig.get(HBaseKey.HBASE_THIN_CONNECT_PASSWORD);
            if (Strings.isNullOrEmpty(thinConnectStr)) {
                throw AddaxException.asAddaxException(
                        HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                        "You must configure 'hbase.thin.connect.url' if your want use thinClient mode");
            }
            if (Strings.isNullOrEmpty(cfg.namespace) || Strings.isNullOrEmpty(cfg.username) || Strings
                    .isNullOrEmpty(cfg.password)) {
                throw AddaxException.asAddaxException(HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                        "The items 'hbase.thin.connect.namespace|username|password' must be configured if you want to use thinClient mode");
            }
            cfg.connectionString = thinConnectStr;
        }
        else {
            // 解析zk服务器和znode信息
            Pair<String, String> zkCfg;
            try {
                zkCfg = HbaseSQLHelper.getHbaseConfig(hbaseCfg);
            }
            catch (Throwable t) {
                // 解析hbase配置错误
                throw AddaxException.asAddaxException(
                        HbaseSQLWriterErrorCode.REQUIRED_VALUE,
                        "Failed to parse hbaseConfig，please check it.");
            }
            String zkQuorum = zkCfg.getFirst();
            String znode = zkCfg.getSecond();
            if (zkQuorum == null || zkQuorum.isEmpty() || znode == null || znode.isEmpty()) {
                throw AddaxException.asAddaxException(
                        HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                        "The items hbase.zookeeper.quorum/zookeeper.znode.parent must be configured");
            }

            // 生成sql使用的连接字符串， 格式： jdbc:phoenix:zk_quorum[:port]:/znode_parent[:principal:keytab]
            cfg.connectionString = "jdbc:phoenix:" + zkQuorum + ":" + znode;
        }
    }

    private static void parseTableConfig(HbaseSQLWriterConfig cfg, Configuration jobConf)
    {
        // 解析并检查表名
        cfg.tableName = jobConf.getString(HBaseKey.TABLE);

        if (cfg.tableName == null || cfg.tableName.isEmpty()) {
            throw AddaxException.asAddaxException(
                    HbaseSQLWriterErrorCode.ILLEGAL_VALUE, "The item tableName must be configured.");
        }
        try {
            TableName.valueOf(cfg.tableName);
        }
        catch (Exception e) {
            throw AddaxException.asAddaxException(
                    HbaseSQLWriterErrorCode.ILLEGAL_VALUE,
                    "The table " +  cfg.tableName + " you configured has illegal symbols.");
        }

        // 解析列配置
        cfg.columns = jobConf.getList(HBaseKey.COLUMN, String.class);
        if (cfg.columns == null || cfg.columns.isEmpty()) {
            throw AddaxException.asAddaxException(
                    HbaseSQLWriterErrorCode.ILLEGAL_VALUE, "The item columns must be configured and can not be empty.");
        }
    }

    /**
     * @return 获取原始配置
     */
    public Configuration getOriginalConfig()
    {
        return originalConfig;
    }

    /**
     * @return 获取连接字符串，使用ZK模式
     */
    public String getConnectionString()
    {
        return connectionString;
    }

    /**
     * @return 获取表名
     */
    public String getTableName()
    {
        return tableName;
    }

    /**
     * @return 返回所有的列，包括主键列和非主键列，但不包括version列
     */
    public List<String> getColumns()
    {
        return columns;
    }

    public NullModeType getNullMode()
    {
        return nullMode;
    }

    /**
     * @return 批量写入的最大行数
     */
    public int  getBatchSize()
    {
        return batchSize;
    }

    /**
     * @return 在writer初始化的时候是否要清空目标表
     */
    public boolean truncate()
    {
        return truncate;
    }

    public boolean isThinClient()
    {
        return isThinClient;
    }

    public String getNamespace()
    {
        return namespace;
    }

    public String getPassword()
    {
        return password;
    }

    public String getUsername()
    {
        return username;
    }

    public boolean haveKerberos()
    {
        return haveKerberos;
    }

    public String getKerberosKeytabFilePath()
    {
        return kerberosKeytabFilePath;
    }

    public String getKerberosPrincipal()
    {
        return kerberosPrincipal;
    }

    @Override
    public String toString()
    {
        StringBuilder ret = new StringBuilder();
        // 集群配置
        ret.append("\n[jdbc]");
        ret.append(connectionString);
        ret.append("\n");

        // 表配置
        ret.append("[tableName]");
        ret.append(tableName);
        ret.append("\n");
        ret.append("[column]");
        for (String col : columns) {
            ret.append(col);
            ret.append(",");
        }
        ret.setLength(ret.length() - 1);
        ret.append("\n");

        // 其他配置
        ret.append("[nullMode]");
        ret.append(nullMode);
        ret.append("\n");
        ret.append("[batchSize]");
        ret.append(batchSize);
        ret.append("\n");
        ret.append("[truncate]");
        ret.append(truncate);
        ret.append("\n");

        return ret.toString();
    }
}
