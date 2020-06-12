/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.apimgt.migration.client.sp_migration;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.client.MigrationClient;
import org.wso2.carbon.apimgt.migration.client.MigrationClientBase;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.sql.SQLException;
import java.util.List;

public class APIMStatMigrationClient extends MigrationClientBase implements MigrationClient {

    private static final Log log = LogFactory.getLog(APIMStatMigrationClient.class);

    private RegistryService registryService;

    public APIMStatMigrationClient(String tenantArguments, String blackListTenantArguments, String tenantRange,
                                   RegistryService registryService, TenantManager tenantManager)
            throws UserStoreException, APIMigrationException {
        super(tenantArguments, blackListTenantArguments, tenantRange, tenantManager);
        this.registryService = registryService;
    }

    private void statDbMigration() throws APIMStatMigrationException {
        String tableNames = System.getProperty(APIMStatMigrationConstants.ARG_TABLE_NAME);
        String[] tableNamesArray;
        if (tableNames != null) {
            tableNamesArray = tableNames.split(",");
        } else {
            tableNamesArray = new String[13];
            tableNamesArray[0] = APIMStatMigrationConstants.API_PER_DESTINATION_AGG;
            tableNamesArray[1] = APIMStatMigrationConstants.API_RESOURCE_PATH_AGG;
            tableNamesArray[2] = APIMStatMigrationConstants.API_VERSION_USAGE_AGG;
            tableNamesArray[3] = APIMStatMigrationConstants.API_LAST_ACCESS_SUMMARY_AGG;
            tableNamesArray[4] = APIMStatMigrationConstants.API_FAULTY_INVOCATION_AGG;
            tableNamesArray[5] = APIMStatMigrationConstants.API_USER_BROWSER_AGG;
            tableNamesArray[6] = APIMStatMigrationConstants.API_GEO_LOCATION_AGG;
            tableNamesArray[7] = APIMStatMigrationConstants.API_EXEC_TIME_AGG_DAY;
            tableNamesArray[8] = APIMStatMigrationConstants.API_EXEC_TIME_AGG_HOUR;
            tableNamesArray[9] = APIMStatMigrationConstants.API_EXEC_TIME_AGG_MINUTE;
            tableNamesArray[10] = APIMStatMigrationConstants.API_THROTTLED_OUT_AGG;
            tableNamesArray[11] = APIMStatMigrationConstants.APIM_REQ_COUNT_AGG;
            tableNamesArray[12] = APIMStatMigrationConstants.API_USER_PER_APP_AGG;
        }
        log.info("Started stat db migration......");
        DBManager dbManager = new DBManagerImpl();
        for (int i = 0; tableNamesArray.length > i; i++) {
            String table = tableNamesArray[i];
            switch (table) {
                case APIMStatMigrationConstants.API_PER_DESTINATION_AGG:
                    log.info("----------------Started migrating Destination Summary table------------------");
                    dbManager.migrateDestinationSummaryTable();
                    log.info("----------------Completed migrating Destination Summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_RESOURCE_PATH_AGG:
                    log.info("----------------Started migrating Resource usage summary table------------------");
                    dbManager.migrateResourceUsageSummaryTable();
                    log.info("----------------Completed migrating Resource usage summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_VERSION_USAGE_AGG:
                    log.info("----------------Started migrating Version usage summary table------------------");
                    dbManager.migrateVersionUsageSummaryTable();
                    log.info("----------------Completed migrating Version usage summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_LAST_ACCESS_SUMMARY_AGG:
                    log.info("----------------Started migrating Last access time summary table------------------");
                    dbManager.migrateLastAccessTimeSummaryTable();
                    log.info("----------------Completed migrating Last access time summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_FAULTY_INVOCATION_AGG:
                    log.info("----------------Started migrating Fault summary table------------------");
                    dbManager.migrateFaultSummaryTable();
                    log.info("----------------Completed migrating Fault summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_USER_BROWSER_AGG:
                    log.info("----------------Started migrating User browser summary table------------------");
                    dbManager.migrateUserBrowserSummaryTable();
                    log.info("----------------Completed migrating User browser summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_GEO_LOCATION_AGG:
                    log.info("----------------Started migrating Geo location summary table------------------");
                    dbManager.migrateGeoLocationSummaryTable();
                    log.info("----------------Completed migrating Geo location summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_EXEC_TIME_AGG_DAY:
                    log.info("----------------Started migrating Execution time day summary table------------------");
                    dbManager.migrateExecutionTimeDaySummaryTable();
                    log.info("----------------Completed migrating Execution time day summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_EXEC_TIME_AGG_HOUR:
                    log.info("----------------Started migrating Execution time hour summary table------------------");
                    dbManager.migrateExecutionTimeHourSummaryTable();
                    log.info("----------------Completed migrating Execution time hour summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_EXEC_TIME_AGG_MINUTE:
                    log.info("----------------Started migrating Execution time minute summary table------------------");
                    dbManager.migrateExecutionTimeMinuteSummaryTable();
                    log.info("----------------Completed migrating Execution time minute summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_THROTTLED_OUT_AGG:
                    log.info("----------------Started migrating Throttled out summary table------------------");
                    dbManager.migrateThrottledOutSummaryTable();
                    log.info("----------------Completed migrating Throttled out summary table------------------");
                    break;
                case APIMStatMigrationConstants.APIM_REQ_COUNT_AGG:
                    log.info("----------------Started migrating Throttled out request count summary table------------------");
                    dbManager.migrateThrottledOutRequestCountSummaryTable();
                    log.info("----------------Completed migrating Throttled out request count summary table------------------");
                    break;
                case APIMStatMigrationConstants.API_USER_PER_APP_AGG:
                    log.info("----------------Started migrating Request summary table------------------");
                    dbManager.migrateRequestSummaryTable();
                    log.info("----------------Completed migrating Request summary table------------------");
                    break;
                default:
                    return;
            }
        }
        log.info("Completed stat db migration successfully.....");
    }

    //Methods do not need to implement here
    @Override
    public void databaseMigration() throws APIMigrationException, SQLException {
    }

    @Override
    public void registryResourceMigration() throws APIMigrationException {
    }

    @Override
    public void fileSystemMigration() throws APIMigrationException {
    }

    @Override
    public void cleanOldResources() throws APIMigrationException {
    }

    @Override
    public void statsMigration() throws APIMigrationException, APIMStatMigrationException {
        statDbMigration();
    }

    @Override
    public void tierMigration(List<String> options) throws APIMigrationException {
    }

    @Override
    public void updateArtifacts() throws APIMigrationException {
    }

    @Override
    public void populateSPAPPs() throws APIMigrationException {
    }

    @Override
    public void userRolesMigration() throws APIMigrationException {
    }
}
