/*
* Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.dao.constants.SQLConstants;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.registry.core.exceptions.RegistryException;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.FileUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.io.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;

public abstract class MigrationClientBase {
    private static final Log log = LogFactory.getLog(MigrationClientBase.class);
    private List<Tenant> tenantsArray;
    private static final String IS_MYSQL_SESION_MODE_EXISTS = "SELECT COUNT(@@SESSION.sql_mode)";
    private static final String GET_MYSQL_SESSION_MODE = "SELECT @@SESSION.sql_mode AS MODE";
    private  static final String NO_ZERO_DATE_MODE = "NO_ZERO_DATE";

    public MigrationClientBase(String tenantArguments, String blackListTenantArguments, String tenantRange,
            TenantManager tenantManager) throws UserStoreException {
        if (tenantArguments != null) {  // Tenant arguments have been provided so need to load specific ones
            tenantArguments = tenantArguments.replaceAll("\\s", ""); // Remove spaces and tabs

            tenantsArray = new ArrayList<>();

            buildTenantList(tenantManager, tenantsArray, tenantArguments);
        } else if (blackListTenantArguments != null) {
            blackListTenantArguments = blackListTenantArguments.replaceAll("\\s", ""); // Remove spaces and tabs

            List<Tenant> blackListTenants = new ArrayList<>();
            buildTenantList(tenantManager, blackListTenants, blackListTenantArguments);

            List<Tenant> allTenants = new ArrayList<>(Arrays.asList(tenantManager.getAllTenants()));
            Tenant superTenant = new Tenant();
            superTenant.setDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            superTenant.setId(MultitenantConstants.SUPER_TENANT_ID);
            allTenants.add(superTenant);

            tenantsArray = new ArrayList<>();

            for (Tenant tenant : allTenants) {
                boolean isBlackListed = false;
                for (Tenant blackListTenant : blackListTenants) {
                    if (blackListTenant.getId() == tenant.getId()) {
                        isBlackListed = true;
                        break;
                    }
                }

                if (!isBlackListed) {
                    tenantsArray.add(tenant);
                }
            }
        } else if (tenantRange != null) {
            tenantsArray = new ArrayList<Tenant>();
            int l, u;
            try {
                l = Integer.parseInt(tenantRange.split("-")[0].trim());
                u = Integer.parseInt(tenantRange.split("-")[1].trim());
            } catch (Exception e) {
                throw new UserStoreException("TenantRange argument is not properly set. use format 1-12", e);
            }
            log.debug("no of Tenants " + tenantManager.getAllTenants().length);
            int lastIndex = tenantManager.getAllTenants().length - 1;
            log.debug("last Tenant id " + tenantManager.getAllTenants()[lastIndex].getId());
            for (Tenant t : tenantManager.getAllTenants()) {
                if (t.getId() > l && t.getId() < u) {
                    log.debug("using tenants " + t.getDomain() + "(" + t.getId() + ")");
                    tenantsArray.add(t);
                }
            }
        } else {  // Load all tenants
            tenantsArray = new ArrayList<>(Arrays.asList(tenantManager.getAllTenants()));
            Tenant superTenant = new Tenant();
            superTenant.setDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
            superTenant.setId(MultitenantConstants.SUPER_TENANT_ID);
            tenantsArray.add(superTenant);
        }
        setAdminUserName(tenantManager);
    }

    private void buildTenantList(TenantManager tenantManager, List<Tenant> tenantList, String tenantArguments)
            throws UserStoreException {
        if (tenantArguments.contains(",")) { // Multiple arguments specified
            String[] parts = tenantArguments.split(",");

            for (String part : parts) {
                if (part.length() > 0) {
                    populateTenants(tenantManager, tenantList, part);
                }
            }
        } else { // Only single argument provided
            populateTenants(tenantManager, tenantList, tenantArguments);
        }
    }

    private void populateTenants(TenantManager tenantManager, List<Tenant> tenantList, String argument) throws UserStoreException {

        if (log.isDebugEnabled()) {
            log.debug("Argument provided : " + argument);
        }

        if (argument.contains("@")) { // Username provided as argument
            int tenantID = tenantManager.getTenantId(argument);

            if (tenantID != -1) {
                tenantList.add(tenantManager.getTenant(tenantID));
            } else {
                log.error("Tenant does not exist for username " + argument);
            }
        } else { // Domain name provided as argument
            if (MultitenantConstants.SUPER_TENANT_DOMAIN_NAME.equalsIgnoreCase(argument)) {
                Tenant superTenant = new Tenant();
                superTenant.setDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
                superTenant.setId(MultitenantConstants.SUPER_TENANT_ID);
                tenantList.add(superTenant);
            }
            else {
                Tenant[] tenants = tenantManager.getAllTenantsForTenantDomainStr(argument);

                if (tenants.length > 0) {
                    tenantList.addAll(Arrays.asList(tenants));
                } else {
                    log.error("Tenant does not exist for domain " + argument);
                }
            }
        }
    }

    private void setAdminUserName(TenantManager tenantManager) throws UserStoreException {
        log.debug("Setting tenant admin names");

        for (int i = 0; i < tenantsArray.size(); ++i) {
            Tenant tenant = tenantsArray.get(i);
            if (tenant.getId() == MultitenantConstants.SUPER_TENANT_ID) {
                tenant.setAdminName("admin");
            }
            else {
                tenantsArray.set(i, tenantManager.getTenant(tenant.getId()));
            }
        }
    }

    protected List<Tenant> getTenantsArray() { return tenantsArray; }

    protected void updateAPIManagerDatabase(String sqlScriptPath) throws SQLException {
        log.info("Database migration for API Manager started");

        Connection connection = null;
        PreparedStatement preparedStatement = null;
        Statement statement = null;
        ResultSet resultSet = null;

        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            String dbType = MigrationDBCreator.getDatabaseType(connection);

            if (Constants.DB_TYPE_MYSQL.equals(dbType)) {
                statement = connection.createStatement();
                resultSet = statement.executeQuery(GET_MYSQL_SESSION_MODE);

                if (resultSet.next()) {
                    String mode = resultSet.getString("MODE");

                    log.info("MySQL Server SQL Mode is : " + mode);

                    if (mode.contains(NO_ZERO_DATE_MODE)) {
                        File timeStampFixScript = new File(sqlScriptPath + dbType + "-timestamp_fix.sql");

                        if (timeStampFixScript.exists()) {
                            log.info(NO_ZERO_DATE_MODE + " mode detected, run schema compatibility script");
                            InputStream is = new FileInputStream(timeStampFixScript);

                            List<String> sqlStatements = readSQLStatements(is, dbType);

                            for (String sqlStatement : sqlStatements) {
                                preparedStatement = connection.prepareStatement(sqlStatement);
                                preparedStatement.execute();
                                connection.commit();
                            }
                        }
                    }
                }
            }

            InputStream is = new FileInputStream(sqlScriptPath + dbType + ".sql");

            List<String> sqlStatements = readSQLStatements(is, dbType);
            for (String sqlStatement : sqlStatements) {
                log.debug("SQL to be executed : " + sqlStatement);
                if (Constants.DB_TYPE_ORACLE.equals(dbType)) {
                    statement = connection.createStatement();
                    statement.executeUpdate(sqlStatement);
                } else {
                    preparedStatement = connection.prepareStatement(sqlStatement);
                    preparedStatement.execute();
                }
            }
            connection.commit();

        }  catch (Exception e) {
            /* MigrationDBCreator extends from org.wso2.carbon.utils.dbcreator.DatabaseCreator and in the super class
            method getDatabaseType throws generic Exception */
            log.error("Error occurred while migrating databases", e);
            connection.rollback();
        } finally {
            if (resultSet != null) {
                resultSet.close();
            }

            if (statement != null) {
                statement.close();
            }
            if (preparedStatement != null) {
                preparedStatement.close();
            }
            if (connection != null) {
                connection.close();
            }
        }
        log.info("DB resource migration done for all the tenants");
    }

    /**
     * This method is used to remove the FK constraint which is unnamed
     * This finds the name of the constraint and build the query to delete the constraint and execute it
     *
     * @param sqlScriptPath path of sql script
     * @throws SQLException
     */
    protected void dropFKConstraint(String sqlScriptPath) throws SQLException {
        Connection connection = null;
        PreparedStatement preparedStatement = null;
        ResultSet resultSet = null;
        Statement statement = null;
        try {
            connection = APIMgtDBUtil.getConnection();
            String dbType = MigrationDBCreator.getDatabaseType(connection);
            String queryToExecute = IOUtils.toString(
                    new FileInputStream(new File(sqlScriptPath + "constraint" + File.separator + dbType + ".sql")),
                    "UTF-8");
            String queryArray[] = queryToExecute.split(Constants.LINE_BREAK);
            connection.setAutoCommit(false);
            statement = connection.createStatement();
            if (Constants.DB_TYPE_ORACLE.equals(dbType)) {
                queryArray[0] = queryArray[0].replace(Constants.DELIMITER, "");
                queryArray[0] = queryArray[0].replace("<AM_DB_NAME>", connection.getMetaData().getUserName());
            }
            resultSet = statement.executeQuery(queryArray[0]);
            String constraintName = null;

            while (resultSet.next()) {
                constraintName = resultSet.getString("constraint_name");
            }

            if (constraintName != null) {
                queryToExecute = queryArray[1].replace("<temp_key_name>", constraintName);
                if (Constants.DB_TYPE_ORACLE.equals(dbType)) {
                    queryToExecute = queryToExecute.replace(Constants.DELIMITER, "");
                }

                if (queryToExecute.contains("\\n")) {
                    queryToExecute = queryToExecute.replace("\\n", "");
                }
                preparedStatement = connection.prepareStatement(queryToExecute);
                preparedStatement.execute();
                connection.commit();
            }
        } catch (APIMigrationException e) {
            //Foreign key might be already deleted, log the error and let it continue
            log.error("Error occurred while deleting foreign key", e);
        } catch (IOException e) {
            //If user does not add the file migration will continue and migrate the db without deleting
            // the foreign key reference
            log.error("Error occurred while finding the foreign key deletion query for execution", e);
        } catch (Exception e) {
            /* MigrationDBCreator extends from org.wso2.carbon.utils.dbcreator.DatabaseCreator and in the super class
            method getDatabaseType throws generic Exception */
            log.error("Error occurred while deleting foreign key", e);
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    log.error("Unable to close the statement", e);
                }
            }
            APIMgtDBUtil.closeAllConnections(preparedStatement, connection, resultSet);
        }
    }

    private List<String> readSQLStatements(InputStream is, String dbType) {
        List<String> sqlStatements = new ArrayList<>();

        try {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is, "UTF8"));
            String sqlQuery = "";
            boolean isFoundQueryEnd = false;
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("//") || line.startsWith("--")) {
                    continue;
                }
                StringTokenizer stringTokenizer = new StringTokenizer(line);
                if (stringTokenizer.hasMoreTokens()) {
                    String token = stringTokenizer.nextToken();
                    if ("REM".equalsIgnoreCase(token)) {
                        continue;
                    }
                }

                if (line.contains("\\n")) {
                    line = line.replace("\\n", "");
                }

                sqlQuery += ' ' + line;
                if (line.contains(";")) {
                    isFoundQueryEnd = true;
                }

                if (org.wso2.carbon.apimgt.migration.util.Constants.DB_TYPE_ORACLE.equals(dbType)) {
                    if ("/".equals(line.trim())) {
                        isFoundQueryEnd = true;
                    } else {
                        isFoundQueryEnd = false;
                    }
                    sqlQuery = sqlQuery.replaceAll("/", "");
                }
                if (org.wso2.carbon.apimgt.migration.util.Constants.DB_TYPE_DB2.equals(dbType)) {
                    sqlQuery = sqlQuery.replace(";", "");
                }

                if (isFoundQueryEnd) {
                    if (sqlQuery.length() > 0) {
                        if (log.isDebugEnabled()) {
                            log.debug("SQL to be executed : " + sqlQuery);
                        }

                        sqlStatements.add(sqlQuery.trim());
                    }

                    // Reset variables to read next SQL
                    sqlQuery = "";
                    isFoundQueryEnd = false;
                }
            }

            bufferedReader.close();
        }  catch (IOException e) {
            log.error("Error while reading SQL statements from stream", e);
        }

        return sqlStatements;
    }


    /**
     * This method is used to update the API artifacts in the registry
     * - to migrate Publisher Access Control feature related data.
     * - to add overview_type property to API artifacts
     *
     * @throws APIMigrationException
     */
    public void updateGenericAPIArtifacts(RegistryService registryService) throws APIMigrationException {
        for (Tenant tenant : getTenantsArray()) {
            try {
                registryService.startTenantFlow(tenant);
                log.debug("Updating APIs for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();
                for (GenericArtifact artifact : artifacts) {
                    String path = artifact.getPath();
                    if (registryService.isGovernanceRegistryResourceExists(path)) {
                        Object apiResource = registryService.getGovernanceRegistryResource(path);
                        if (apiResource == null) {
                            continue;
                        }
                        registryService.updateGenericAPIArtifactsForAccessControl(path, artifact);
                        registryService.updateGenericAPIArtifact(path, artifact);
                    }
                }
                log.info("Completed Updating API artifacts tenant ---- " + tenant.getId() + '(' + tenant.getDomain() + ')');
            } catch (GovernanceException e) {
                log.error("Error while accessing API artifact in registry for tenant " + tenant.getId() + '(' +
                        tenant.getDomain() + ')', e);
            } catch (RegistryException | UserStoreException e) {
                log.error("Error while updating API artifact in the registry for tenant " + tenant.getId() + '(' +
                        tenant.getDomain() + ')', e);
            } finally {
                registryService.endTenantFlow();
            }
        }
    }

    public void migrateFaultSequencesInRegistry(RegistryService registryService) {

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

        if (StringUtils.isEmpty(apim210FaultSequenceContent) && StringUtils.isEmpty(apim210DebugFaultSequenceContent)) {
            // nothing has been read from <APIM_NEW_HOME>/repository/resources/customsequences/fault
            log.error("No content read from <APIM_NEW_HOME>/repository/resources/customsequences/fault location, "
                    + "aborting migration");
            return;
        }
        for (Tenant tenant : getTenantsArray()) {
            try {
                registryService.startTenantFlow(tenant);
                // update json_fault.xml and debug_json_fault.xml in registry
                if (StringUtils.isNotEmpty(apim210FaultSequenceContent)) {
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
                if (StringUtils.isNotEmpty(apim210DebugFaultSequenceContent)) {
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
}
