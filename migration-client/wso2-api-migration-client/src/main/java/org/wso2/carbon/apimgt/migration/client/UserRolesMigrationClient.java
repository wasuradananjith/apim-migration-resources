/*
 * Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.dao.SharedDAO;
import org.wso2.carbon.apimgt.migration.dto.UserRoleFromPermissionDTO;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.apimgt.migration.client.sp_migration.APIMStatMigrationException;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.core.tenant.TenantManager;

import java.sql.SQLException;
import java.util.List;

public class UserRolesMigrationClient extends MigrationClientBase implements MigrationClient {
    private static final Log log = LogFactory.getLog(UserRolesMigrationClient.class);
    private RegistryService registryService;

    public UserRolesMigrationClient(String tenantArguments, String blackListTenantArguments, String tenantRange,
                                        RegistryService registryService, TenantManager tenantManager) throws UserStoreException {
        super(tenantArguments, blackListTenantArguments, tenantRange, tenantManager);
        this.registryService = registryService;
    }

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
        log.info("User roles migration started");


        updateUserRoles();
    }

    private void printArray(UserRoleFromPermissionDTO[] userRoleFromPermissionDTOs){
        for (int i = 0; i < userRoleFromPermissionDTOs.length; i++){
            System.out.println("Index: " + i);
            System.out.println("User Role Name: " + userRoleFromPermissionDTOs[i].getUserRoleName());
            System.out.println("User Domain Name: " + userRoleFromPermissionDTOs[i].getUserRoleDomainName() + "\n");
        }
    }

    /**
     * This method is used to update the scopes of the user roles which will be retrieved based on the
     * permissions assigned.
     *
     * @throws APIMigrationException
     */
    public void updateUserRoles() {
        log.info("Updating User Roles based on Permissions started.");
        for (Tenant tenant : getTenantsArray()) {
            try {
                registryService.startTenantFlow(tenant);

                log.info("Updating user roles for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');

                try {
                    UserRoleFromPermissionDTO[] userRolesListWithCreatePermission = SharedDAO.getInstance().getRoleNamesMatchingPermission(APIConstants.Permissions.API_CREATE, tenant.getId());
                    UserRoleFromPermissionDTO[] userRolesListWithPublishPermission = SharedDAO.getInstance().getRoleNamesMatchingPermission(APIConstants.Permissions.API_PUBLISH, tenant.getId());
                    UserRoleFromPermissionDTO[] userRolesListWithSubscribePermission = SharedDAO.getInstance().getRoleNamesMatchingPermission(APIConstants.Permissions.API_SUBSCRIBE, tenant.getId());

                    log.info("-----------------CREATE------------------");
                    printArray(userRolesListWithCreatePermission);
                    log.info("-----------------PUBLISH------------------");
                    printArray(userRolesListWithPublishPermission);
                    log.info("-----------------SUBSCRIBE------------------");
                    printArray(userRolesListWithSubscribePermission);

                } catch (APIManagementException e) {
                    log.error("Error while retrieving role names based on existing permissions. ", e);
                }

//                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();
//                for (GenericArtifact artifact : artifacts) {
//                    String path = artifact.getPath();
//                    if (registryService.isGovernanceRegistryResourceExists(path)) {
//                        Object apiResource = registryService.getGovernanceRegistryResource(path);
//                        if (apiResource == null) {
//                            continue;
//                        }
//                        registryService.updateGenericAPIArtifactsForAccessControl(path, artifact);
//                    }
//                }
                log.info("End updating user roles for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
            } finally {
                registryService.endTenantFlow();
            }
        }
        log.info("Updating API artifacts done for all the tenants for Publisher Access Control feature.");
    }
}
