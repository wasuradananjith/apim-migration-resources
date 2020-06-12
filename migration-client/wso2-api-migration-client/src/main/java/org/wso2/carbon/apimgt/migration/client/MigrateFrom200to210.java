/*
* Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.wso2.carbon.apimgt.migration.client;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.FileUtil;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

public class MigrateFrom200to210 extends MigrationClientBase implements MigrationClient {

    private static final Log log = LogFactory.getLog(MigrateFrom200to210.class);
    private RegistryService registryService;

    public MigrateFrom200to210(String tenantArguments, String blackListTenantArguments, String tenantRange, RegistryService
            registryService, TenantManager tenantManager)
            throws UserStoreException {
        super(tenantArguments, blackListTenantArguments, tenantRange, tenantManager);
        this.registryService = registryService;
    }

    @Override
    public void databaseMigration() throws APIMigrationException, SQLException {
    }

    @Override
    public void registryResourceMigration() throws APIMigrationException {
        migrateFaultSequencesInRegistry();
    }

    @Override
    public void fileSystemMigration() throws APIMigrationException {
    }

    @Override
    public void cleanOldResources() throws APIMigrationException {
    }

    @Override
    public void statsMigration() throws APIMigrationException {
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

    private void migrateFaultSequencesInRegistry() {

        /* change the APIMgtFaultHandler class name in debug_json_fault.xml and json_fault.xml
           this method will read the new *json_fault.xml sequences from
           <APIM_2.1.0_HOME>/repository/resources/customsequences/fault and overwrite what is there in registry for
           all the tenants*/
        log.info("Fault sequence migration from APIM 2.0.0 to 2.1.0 has started");
        String apim210FaultSequencesLocation = CarbonUtils.getCarbonHome() + File.separator + "repository" + File
                .separator + "resources" + File.separator + "customsequences" + File.separator + "fault";
        String apim210FaultSequenceFile = apim210FaultSequencesLocation + File.separator + "json_fault.xml";
        String api210DebugFaultSequenceFile = apim210FaultSequencesLocation + File.separator + "debug_json_fault.xml";

        // read new files
        String apim210FaultSequenceContent = null;
        try {
            apim210FaultSequenceContent = FileUtil.readFileToString(apim210FaultSequenceFile);
        } catch (IOException e) {
            log.error("Error in reading file: " + apim210FaultSequenceFile, e);
        }

        String apim210DebugFaultSequenceContent = null;
        try {
            apim210DebugFaultSequenceContent = FileUtil.readFileToString(api210DebugFaultSequenceFile);
        } catch (IOException e) {
            log.error("Error in reading file: " + api210DebugFaultSequenceFile, e);
        }

        if (isEmpty(apim210FaultSequenceContent) && isEmpty(apim210DebugFaultSequenceContent)) {
            // nothing has been read from <APIM_2.1.0_HOME>/repository/resources/customsequences/fault
            log.error("No content read from <APIM_2.1.0_HOME>/repository/resources/customsequences/fault location, "
                    + "aborting migration");
            return;
        }
        for (Tenant tenant : getTenantsArray()) {
            try {
                registryService.startTenantFlow(tenant);
                // update json_fault.xml and debug_json_fault.xml in registry
                if (!isEmpty(apim210FaultSequenceContent)) {
                    try {
                        final String jsonFaultResourceRegistryLocation = "/apimgt/customsequences/fault/json_fault.xml";
                        if (registryService.isGovernanceRegistryResourceExists(jsonFaultResourceRegistryLocation)) {
                            // update
                            registryService.updateGovernanceRegistryResource(jsonFaultResourceRegistryLocation,
                                    apim210FaultSequenceContent);
                        } else {
                            // add
                            registryService.addGovernanceRegistryResource(jsonFaultResourceRegistryLocation,
                                    apim210FaultSequenceContent, "application/xml");
                        }
                        log.info("Successfully migrated json_fault.xml in registry for tenant: " + tenant.getDomain() +
                                ", tenant id: " + tenant.getId());

                    } catch (UserStoreException e) {
                        log.error("Error in updating json_fault.xml in registry for tenant: " + tenant.getDomain() +
                                ", tenant id: " + tenant.getId(), e);
                    } catch (RegistryException e) {
                        log.error("Error in updating json_fault.xml in registry for tenant: " + tenant.getDomain() +
                                ", tenant id: " + tenant.getId(), e);
                    }
                }
                if (!isEmpty(apim210DebugFaultSequenceContent)) {
                    try {
                        final String debugJsonFaultResourceRegistryLocation = "/apimgt/customsequences/fault/debug_json_fault.xml";
                        if (registryService.isGovernanceRegistryResourceExists(debugJsonFaultResourceRegistryLocation)) {
                            // update
                            registryService.updateGovernanceRegistryResource(debugJsonFaultResourceRegistryLocation,
                                    apim210DebugFaultSequenceContent);
                        } else {
                            // add
                            registryService.addGovernanceRegistryResource(debugJsonFaultResourceRegistryLocation,
                                    apim210DebugFaultSequenceContent, "application/xml");
                        }
                        log.info("Successfully migrated debug_json_fault.xml in registry for tenant: " +
                                tenant.getDomain() + ", tenant id: " + tenant.getId());
                    } catch (UserStoreException e) {
                        log.error("Error in updating debug_json_fault.xml in registry for tenant: " +
                                tenant.getDomain() + ", tenant id: " + tenant.getId(), e);
                    } catch (RegistryException e) {
                        log.error("Error in updating debug_json_fault.xml in registry for tenant: " +
                                tenant.getDomain() + ", tenant id: " + tenant.getId(), e);
                    }
                }
            } finally {
                registryService.endTenantFlow();
            }
        }
    }

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }
}
