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


import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.policy.APIPolicy;
import org.wso2.carbon.apimgt.api.model.policy.ApplicationPolicy;
import org.wso2.carbon.apimgt.api.model.policy.PolicyConstants;
import org.wso2.carbon.apimgt.api.model.policy.QuotaPolicy;
import org.wso2.carbon.apimgt.api.model.policy.RequestCountLimit;
import org.wso2.carbon.apimgt.api.model.policy.SubscriptionPolicy;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.template.APITemplateException;
import org.wso2.carbon.apimgt.impl.template.ThrottlePolicyTemplateBuilder;
import org.wso2.carbon.apimgt.impl.throttling.GlobalThrottleEngineClient;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.migration.APIMigrationException;
import org.wso2.carbon.apimgt.migration.client._110Specific.dto.SynapseDTO;
import org.wso2.carbon.apimgt.migration.client._200Specific.ResourceModifier200;
import org.wso2.carbon.apimgt.migration.client._200Specific.model.Policy;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.RegistryService;
import org.wso2.carbon.apimgt.migration.util.ResourceUtil;
import org.wso2.carbon.governance.api.exception.GovernanceException;
import org.wso2.carbon.governance.api.generic.dataobjects.GenericArtifact;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.user.api.Tenant;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.tenant.TenantManager;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.FileUtil;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

public class MigrateFrom110to200 extends MigrationClientBase implements MigrationClient {

    private static final Log log = LogFactory.getLog(MigrateFrom110to200.class);
    private RegistryService registryService;
    private static GlobalThrottleEngineClient globalThrottleEngineClient;


    public MigrateFrom110to200(String tenantArguments, String blackListTenantArguments, String tenantRange,
                               RegistryService registryService, TenantManager tenantManager, boolean removeDecryptionFailedKeysFromDB)
            throws UserStoreException {
        super(tenantArguments, blackListTenantArguments, tenantRange, tenantManager);
        this.registryService = registryService;
    }

    @Override
    public void databaseMigration() throws APIMigrationException, SQLException {
        String amScriptPath = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator;

        updateAPIManagerDatabase(amScriptPath);
    }

    @Override
    public void registryResourceMigration() throws APIMigrationException {
        swaggerResourceMigration();
        rxtMigration();
    }

    @Override
    public void fileSystemMigration() throws APIMigrationException {
        synapseAPIMigration();
    }

    @Override
    public void cleanOldResources() throws APIMigrationException {

    }

    @Override
    public void updateArtifacts() throws APIMigrationException {
        updateAPAIArtifacts();

    }

    @Override
    public void statsMigration() throws APIMigrationException {
        log.info("Stat Database migration for API Manager started");
        String statScriptPath = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator + "stat" + File.separator;
        try {
            updateAPIManagerDatabase(statScriptPath);
        } catch (SQLException e) {
            log.error("error executing stat migration script", e);
            throw new APIMigrationException(e);
        }
        log.info("Stat DB migration Done");
    }

    /**
     * Implementation of new throttling migration using optional migration argument
     *
     * @param options list of command line options
     * @throws APIMigrationException throws if any exception occured
     */
    @Override
    public void tierMigration(List<String> options) throws APIMigrationException {
        log.info("Advanced throttling migration for API Manager started");
        if (options.contains(Constants.ARG_OPTIONS_MIGRATE_THROTTLING)) {
            boolean deployPolicies = options.contains(Constants.ARG_OPTIONS_DEPLOY_POLICIES);
            if (deployPolicies) {
                log.info("Throttle policies will be deployed to Traffic Manager Node confiigured.");
            } else {
                log.info("Throttle policies will be saved in " + ResourceUtil.getExecutionPlanPath() + ". "
                        + "Please deploy them to the traffic manager node after the migration.");
            }
            for (Tenant tenant : getTenantsArray()) {
                String apiPath = ResourceUtil.getApiPath(tenant.getId(), tenant.getDomain());
                List<SynapseDTO> synapseDTOs = ResourceUtil.getVersionedAPIs(apiPath);
                ResourceModifier200.updateThrottleHandler(synapseDTOs); // Update Throttle Handler

                for (SynapseDTO synapseDTO : synapseDTOs) {
                    ResourceModifier200.transformXMLDocument(synapseDTO.getDocument(), synapseDTO.getFile());
                }

                //Read Throttling Tier from Registry and update databases
                readThrottlingTiersFromRegistry(tenant, deployPolicies);
                migrateUnlimitedTier();
            }
            if (deployPolicies) {
                log.info("Throttle policies are deployed to Traffic Manager Node confiigured.");
            } else {
                log.info("Throttle policies are saved in " + ResourceUtil.getExecutionPlanPath() + ". "
                        + "Please deploy them to the traffic manager node after the migration.");
            }
            log.info("Advanced throttling migration is completed.");
        }
    }

    public void migrateUnlimitedTier() {
        log.info("Unlimited tier migration for API Manager started");
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        for (Tenant tenant : getTenantsArray()) {
            log.info("Unlimited tier migration for tenant " + tenant.getId()
                    + " started");
            int tenantId = tenant.getId();
            String tenantDomain = tenant.getDomain();
            String policyName = "Unlimited";
            // Add application level unlimited throttle policy
            ApplicationPolicy applicationPolicy = new ApplicationPolicy(
                    policyName);
            applicationPolicy.setDescription("Allows unlimited requests");
            applicationPolicy.setTenantId(tenant.getId());
            applicationPolicy.setDeployed(true);
            applicationPolicy.setTenantDomain(tenant.getDomain());
            QuotaPolicy defaultQuotaPolicy = new QuotaPolicy();
            RequestCountLimit requestCountLimit = new RequestCountLimit();
            requestCountLimit.setRequestCount(Integer.MAX_VALUE);
            requestCountLimit.setUnitTime(1);
            requestCountLimit.setTimeUnit(APIConstants.TIME_UNIT_MINUTE);
            defaultQuotaPolicy.setType(PolicyConstants.REQUEST_COUNT_TYPE);
            defaultQuotaPolicy.setLimit(requestCountLimit);
            applicationPolicy.setDefaultQuotaPolicy(defaultQuotaPolicy);

            try {
                if (!apiMgtDAO.isPolicyExist(PolicyConstants.POLICY_LEVEL_APP,
                        tenant.getId(), "Unlimited")) {
                    apiMgtDAO.addApplicationPolicy(applicationPolicy);
                }
            } catch (APIManagementException e) {
                log.error("Error while migrating Application level Unlimited tier for tenant "
                        + tenant.getId()
                        + '('
                        + tenant.getDomain()
                        + ')'
                        + e.getMessage());
            }

            // Adding Subscription level unlimited throttle policy
            SubscriptionPolicy subscriptionPolicy = new SubscriptionPolicy(
                    policyName);
            subscriptionPolicy.setDisplayName(policyName);
            subscriptionPolicy.setDescription("Allows unlimited requests");
            subscriptionPolicy.setTenantId(tenantId);
            subscriptionPolicy.setDeployed(false);
            subscriptionPolicy.setTenantDomain(tenantDomain);
            defaultQuotaPolicy = new QuotaPolicy();
            requestCountLimit = new RequestCountLimit();
            requestCountLimit.setRequestCount(Integer.MAX_VALUE);
            requestCountLimit.setUnitTime(1);
            requestCountLimit.setTimeUnit(APIConstants.TIME_UNIT_MINUTE);
            defaultQuotaPolicy.setType(PolicyConstants.REQUEST_COUNT_TYPE);
            defaultQuotaPolicy.setLimit(requestCountLimit);
            subscriptionPolicy.setDefaultQuotaPolicy(defaultQuotaPolicy);
            subscriptionPolicy.setStopOnQuotaReach(true);
            subscriptionPolicy.setBillingPlan(APIConstants.BILLING_PLAN_FREE);

            try {
                if (!apiMgtDAO.isPolicyExist(PolicyConstants.POLICY_LEVEL_SUB,
                        tenantId, policyName)) {
                    apiMgtDAO.addSubscriptionPolicy(subscriptionPolicy);
                }
            } catch (APIManagementException e) {
                log.error("Error while migrating Subscription level Unlimited tier for tenant "
                        + tenant.getId()
                        + '('
                        + tenant.getDomain()
                        + ')'
                        + e.getMessage());
            }

            // Adding Resource level unlimited throttle policy
            APIPolicy apiPolicy = new APIPolicy(policyName);
            apiPolicy.setDisplayName(policyName);
            apiPolicy.setDescription("Allows unlimited requests");
            apiPolicy.setTenantId(tenantId);
            apiPolicy.setUserLevel(APIConstants.API_POLICY_API_LEVEL);
            apiPolicy.setDeployed(false);
            apiPolicy.setTenantDomain(tenantDomain);
            defaultQuotaPolicy = new QuotaPolicy();
            requestCountLimit = new RequestCountLimit();
            requestCountLimit.setRequestCount(Integer.MAX_VALUE);
            requestCountLimit.setUnitTime(1);
            requestCountLimit.setTimeUnit(APIConstants.TIME_UNIT_MINUTE);
            defaultQuotaPolicy.setType(PolicyConstants.REQUEST_COUNT_TYPE);
            defaultQuotaPolicy.setLimit(requestCountLimit);
            apiPolicy.setDefaultQuotaPolicy(defaultQuotaPolicy);

            try {
                if (!apiMgtDAO.isPolicyExist(PolicyConstants.POLICY_LEVEL_API,
                        tenantId, policyName)) {
                    apiMgtDAO.addAPIPolicy(apiPolicy);
                }
            } catch (APIManagementException e) {
                log.error("Error while migrating API level Unlimited tier for tenant "
                        + tenant.getId()
                        + '('
                        + tenant.getDomain()
                        + ')'
                        + e.getMessage());
            }
            log.info("Unlimited tier migration for tenant " + tenant.getId()
                    + " completed");

        }
        log.info("Unlimited tier migration for API Manager completed");
    }

    private void synapseAPIMigration() {
        for (Tenant tenant : getTenantsArray()) {
            String apiPath = ResourceUtil.getApiPath(tenant.getId(), tenant.getDomain());
            List<SynapseDTO> synapseDTOs = ResourceUtil.getVersionedAPIs(apiPath);
            ResourceModifier200.updateSynapseConfigs(synapseDTOs);

            for (SynapseDTO synapseDTO : synapseDTOs) {
                ResourceModifier200.transformXMLDocument(synapseDTO.getDocument(), synapseDTO.getFile());
            }
        }
    }

    /**
     * This method is used to update the API artifacts in the registry to trigger indexer.
     *
     *
     * @throws APIMigrationException
     */
    private void updateAPAIArtifacts() throws APIMigrationException {
        log.info("Updating API artifacts for API Manager started.");

        for (Tenant tenant : getTenantsArray()) {
            try {
                registryService.startTenantFlow(tenant);

                log.info("Updating APIs for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');

                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();
                for (GenericArtifact artifact : artifacts) {
                    String path = artifact.getPath();
                    if (registryService.isGovernanceRegistryResourceExists(path)) {
                        Object apiResource = registryService.getGovernanceRegistryResource(path);
                        if (apiResource == null) {
                            continue;
                        }
                        String apiResourceContent = ResourceUtil
                                .getResourceContent(apiResource);
                        if (apiResourceContent == null) {
                            continue;
                        }
                        registryService.updateGovernanceRegistryResource(path, apiResourceContent);
                    }
                }
                log.info("End Updating API artifacts tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');

            } catch (GovernanceException e) {
                log.error("Error when accessing API artifact in registry for tenant " + tenant.getId() + '(' +
                        tenant.getDomain() + ')', e);
            } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
                log.error("Error while updating API artifact in the registry for tenant " + tenant.getId() + '(' +
                        tenant.getDomain() + ')', e);
            } catch (UserStoreException e) {
                log.error("Error while updating API artifact in the registry for tenant " + tenant.getId() + '(' +
                        tenant.getDomain() + ')', e);
            } finally {
                registryService.endTenantFlow();
            }
        }

        log.info("Updating API artifacts done for all the tenants");
    }



    /**
     * This method is used to migrate rxt and rxt data
     * This adds three new attributes to the api rxt
     *
     * @throws APIMigrationException
     */
    private void rxtMigration() throws APIMigrationException {
        log.info("Rxt migration for API Manager started.");

        String rxtName = "api.rxt";
        String rxtDir = CarbonUtils.getCarbonHome() + File.separator + "migration-scripts" + File.separator +
                "110-200-migration" + File.separator + "rxts" + File.separator + rxtName;


        for (Tenant tenant : getTenantsArray()) {
            try {
                registryService.startTenantFlow(tenant);

                log.info("Updating api.rxt for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                //Update api.rxt file
                String rxt = FileUtil.readFileToString(rxtDir);
                registryService.updateRXTResource(rxtName, rxt);
                log.info("End Updating api.rxt for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');

                log.info("Start rxt data migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();
                for (GenericArtifact artifact : artifacts) {
                    String val="{\"corsConfigurationEnabled\": false,"
                            + "\"accessControlAllowOrigins\": [\"*\"],"
                            + "\"accessControlAllowCredentials\": false,"
                            + "\"accessControlAllowHeaders\": [\"authorization\",   \"Access-Control-Allow-Origin\", \"Content-Type\", \"SOAPAction\"],"
                            + "\"accessControlAllowMethods\": [\"GET\", \"PUT\", \"POST\", \"DELETE\", \"PATCH\", \"OPTIONS\"]"
                            + "}";
                    artifact.setAttribute("overview_corsConfiguration", val);
                    if (artifact.getAttribute("overview_endpointSecured") != null) {
                        artifact.setAttribute("overview_endpointSecured", artifact.getAttribute("overview_endpointSecured"));
                    } else {
                        artifact.setAttribute("overview_endpointSecured", "false");
                    }
                    if (artifact.getAttribute("overview_endpointAuthDigest") != null) {
                        artifact.setAttribute("overview_endpointAuthDigest", artifact.getAttribute("overview_endpointAuthDigest"));
                    } else {
                        artifact.setAttribute("overview_endpointAuthDigest", "false");
                    }

                    String env = artifact.getAttribute("overview_environments");
                    if (env == null) {
                        artifact.setAttribute("overview_environments", "Production and Sandbox");
                    }
                    String trans = artifact.getAttribute("overview_transports");
                    if (trans == null) {
                        artifact.setAttribute("overview_transports", "http,https");
                    }
                    String versionType = artifact.getAttribute("overview_versionType");
                    if (versionType == null) {
                        artifact.setAttribute("overview_versionType", "-");
                    }
                }
                registryService.updateGenericAPIArtifacts(artifacts);
                log.info("End rxt data migration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');

            } catch (GovernanceException e) {
                log.error("Error when accessing API artifact in registry for tenant "+ tenant.getId() + '('
                        + tenant.getDomain() + ')', e);
            } catch (IOException e) {
                log.error("Error when reading api.rxt from " + rxtDir + "for tenant " + tenant.getId() + '('
                        + tenant.getDomain() + ')', e);
            } catch (org.wso2.carbon.registry.core.exceptions.RegistryException e) {
                log.error("Error while updating api.rxt in the registry for tenant " + tenant.getId() + '('
                        + tenant.getDomain() + ')', e);
            } catch (UserStoreException e) {
                log.error("Error while updating api.rxt in the registry for tenant " + tenant.getId() + '('
                        + tenant.getDomain() + ')', e);
            }
            finally {
                registryService.endTenantFlow();
            }
        }

        log.info("Rxt resource migration done for all the tenants");
    }

    /**
     * Migrates swagger documentation to be compatible with APIM 2.0.0
     * @throws APIMigrationException
     */
    private void swaggerResourceMigration() throws APIMigrationException {
        log.info("Swagger migration for API Manager " + Constants.VERSION_2_0_0 + " started.");

        for (Tenant tenant : getTenantsArray()) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Start swaggerResourceMigration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
            }

            try {
                registryService.startTenantFlow(tenant);
                GenericArtifact[] artifacts = registryService.getGenericAPIArtifacts();

                updateSwaggerResources(artifacts, tenant);
            } catch (APIMigrationException e) {
                // If any exception happen during a tenant data migration, we continue the other tenants
                log.error("Unable to migrate the swagger resources of tenant : " + tenant.getDomain(), e);
            } finally {
                registryService.endTenantFlow();
            }

            log.debug("End swaggerResourceMigration for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        }

        log.info("Swagger resource migration done for all the tenants.");
    }

    /**
     * This method updates the existing swagger definition for all the APIs of the given tenant
     * @param artifacts API artifacts
     * @param tenant tenant
     * @throws APIMigrationException
     */
    private void updateSwaggerResources(GenericArtifact[] artifacts, Tenant tenant) throws APIMigrationException {
        log.info("Updating Swagger definition for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
        for (GenericArtifact artifact : artifacts) {
            API api = registryService.getAPI(artifact);

            if (api != null) {
                APIIdentifier apiIdentifier = api.getId();
                String apiName = apiIdentifier.getApiName();
                String apiVersion = apiIdentifier.getVersion();
                String apiProviderName = apiIdentifier.getProviderName();
                try {
                    String swaggerlocation = ResourceUtil
                            .getSwagger2ResourceLocation(apiName, apiVersion, apiProviderName);
                    String swaggerDocument = getMigratedSwaggerDefinition(tenant, swaggerlocation, api);

                    if (swaggerDocument != null) {
                        registryService
                                .addGovernanceRegistryResource(swaggerlocation, swaggerDocument, "application/json");
                    }
                } catch (RegistryException e) {
                    log.error("Registry error encountered for api " + apiName + '-' + apiVersion + '-' + apiProviderName
                            + " of tenant " + tenant.getId() + '(' + tenant.getDomain() + ')', e);
                } catch (ParseException e) {
                    log.error("Error occurred while parsing swagger document for api " + apiName + '-' + apiVersion
                                    + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain() + ')',
                            e);
                } catch (UserStoreException e) {
                    log.error(
                            "Error occurred while setting permissions of swagger document for api " + apiName + '-'
                                    + apiVersion + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant
                                    .getDomain() + ')', e);
                } catch (MalformedURLException e) {
                    log.error(
                            "Error occurred while creating swagger document for api " + apiName + '-' + apiVersion
                                    + '-' + apiProviderName + " of tenant " + tenant.getId() + '(' + tenant.getDomain()
                                    + ')', e);
                }
            }
        }
        log.info("End updating Swagger definition for tenant " + tenant.getId() + '(' + tenant.getDomain() + ')');
    }

    /**
     * This method generates swagger definition for APIM 2.0.0
     *
     * @param tenant            Tenant
     * @param swaggerlocation the location of swagger doc
     * @return JSON string of swagger doc
     * @throws java.net.MalformedURLException
     * @throws org.json.simple.parser.ParseException
     * @throws org.wso2.carbon.registry.core.exceptions.RegistryException
     */

    private String getMigratedSwaggerDefinition(Tenant tenant, String swaggerlocation, API api)
            throws MalformedURLException, ParseException, RegistryException, UserStoreException {
        JSONParser parser = new JSONParser();

        Object rawResource = registryService.getGovernanceRegistryResource(swaggerlocation);
        if (rawResource == null) {
            return null;
        }
        String swaggerRes = ResourceUtil.getResourceContent(rawResource);
        if (swaggerRes == null) {
            return null;
        }
        JSONObject swaggerdoc = (JSONObject) parser.parse(swaggerRes);
        JSONObject paths = (JSONObject) swaggerdoc.get(Constants.SWAGGER_PATHS);
        if (paths != null) {
            Set<Map.Entry> res = paths.entrySet();
            for (Map.Entry e : res) {
                JSONObject methods = (JSONObject) e.getValue();
                Set<Map.Entry> mes = methods.entrySet();
                for (Map.Entry m : mes) {
                    if (!(m.getValue() instanceof JSONObject)) {
                        log.warn("path is expected to be json but string found on " + swaggerlocation);
                        continue;
                    }
                    JSONObject re = (JSONObject) m.getValue();
                    //setting produce type as array
                    Object produceObj = re.get(Constants.SWAGGER_PRODUCES);
                    if (produceObj != null && !(produceObj instanceof JSONArray)) {
                        JSONArray prodArr = new JSONArray();
                        prodArr.add((String) produceObj);
                        re.put(Constants.SWAGGER_PRODUCES, prodArr);
                    }

                    //for resources parameters schema changing
                    JSONArray parameters = (JSONArray) re.get(Constants.SWAGGER_PATH_PARAMETERS_KEY);
                    if (parameters != null) {
                        for (int i = 0; i < parameters.size(); i++) {
                            JSONObject parameterObj = (JSONObject) parameters.get(i);
                            JSONObject schemaObj = (JSONObject) parameterObj.get(Constants.SWAGGER_BODY_SCHEMA);
                            if (schemaObj != null) {
                                JSONObject propertiesObj = (JSONObject) schemaObj.get(Constants.SWAGGER_PROPERTIES_KEY);
                                if (propertiesObj == null) {
                                    JSONObject propObj = new JSONObject();
                                    JSONObject payObj = new JSONObject();
                                    payObj.put(Constants.SWAGGER_PARAM_TYPE, Constants.SWAGGER_STRING_TYPE);
                                    propObj.put(Constants.SWAGGER_PAYLOAD_KEY, payObj);
                                    schemaObj.put(Constants.SWAGGER_PROPERTIES_KEY, propObj);
                                }
                            }
                        }
                    }

                    if (re.get(Constants.SWAGGER_RESPONSES) instanceof JSONObject) {
                        //for resources response object
                        JSONObject responses = (JSONObject) re.get(Constants.SWAGGER_RESPONSES);
                        if (responses == null) {
                            log.warn("responses attribute not present in swagger " + swaggerlocation);
                            continue;
                        }
                        JSONObject response;
                        Iterator itr = responses.keySet().iterator();
                        while (itr.hasNext()) {
                            String key = (String) itr.next();
                            if (responses.get(key) instanceof JSONObject) {
                                response = (JSONObject) responses.get(key);
                                boolean isExist = response.containsKey(Constants.SWAGGER_DESCRIPTION);
                                if (!isExist) {
                                    response.put(Constants.SWAGGER_DESCRIPTION, "");
                                }
                            }
                        }
                    } else {
                        log.error("Invalid Swagger responses element found in " + swaggerlocation);
                    }
                }
            }
        }
        return swaggerdoc.toJSONString();
    }


    /**
     * Read all the tiers files of the tenant and deploy as policies
     * @param tenant
     * @throws APIMigrationException
     */
    private void readThrottlingTiersFromRegistry(Tenant tenant, boolean deployPolicies) throws APIMigrationException  {
        registryService.startTenantFlow(tenant);
        try {
            modifySubscriptionPolicyNamesInApiRXT();
            // update or insert all three tiers
            readTiersAndDeploy(tenant, APIConstants.API_TIER_LOCATION, Constants.AM_POLICY_SUBSCRIPTION, deployPolicies);
            readTiersAndDeploy(tenant, APIConstants.APP_TIER_LOCATION, Constants.AM_POLICY_APPLICATION, deployPolicies);
            readTiersAndDeploy(tenant, APIConstants.RES_TIER_LOCATION, Constants.AM_API_THROTTLE_POLICY, deployPolicies);

            Thread.sleep(1000);
        } catch (APIManagementException e) {
            log.error("Error while migrating throttle tiers for tenant "
                    + tenant.getId() + '(' + tenant.getDomain() + ')' + e.getMessage());
        } catch (InterruptedException e) {
            //Thread.sleep was added to slow down the migration client.
        } finally {
            registryService.endTenantFlow();
        }
    }

    /**
     * Deploy the given tier file as a policy
     * @param tenant
     * @param tierFile
     * @param tierType
     * @throws APIMigrationException
     * @throws APIManagementException
     */
    private void readTiersAndDeploy(Tenant tenant, String tierFile,
                                    String tierType, boolean deployPolicies) throws APIMigrationException,
            APIManagementException {
        try {
            if (registryService.getGovernanceRegistryResource(tierFile) != null) {
                Document doc = ResourceUtil
                        .buildDocument((byte[]) registryService.getGovernanceRegistryResource(tierFile), tierFile);

                if (doc != null) {
                    Element rootElement = doc.getDocumentElement();

                    Element throttleAssertion = (Element) rootElement
                            .getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                                    Constants.TIER_MEDIATOR_THROTTLE_ASSERTION_TAG).item(0);

                    NodeList tierNodes = throttleAssertion.getChildNodes();

                    for (int i = 0; i < tierNodes.getLength(); ++i) {
                        Node tierNode = tierNodes.item(i);

                        if (tierNode.getNodeType() == Node.ELEMENT_NODE) {
                            Policy policy = readPolicyFromRegistry(tierNode);
                            boolean hasSpacesInPolicyName = false;
                            String oldPolicyName = policy.getName();
                            String modifiedPolicyName = "";
                            if (oldPolicyName.contains(" ")) {
                                modifiedPolicyName = policy.getName().replace(' ', '_');
                                policy.setName(modifiedPolicyName);
                                hasSpacesInPolicyName = true;
                            }
                            if (Constants.AM_POLICY_SUBSCRIPTION.equals(tierType)) {
                                deploySubscriptionThrottlePolicies(policy, tenant, deployPolicies);
                                if (hasSpacesInPolicyName) {
                                    updatePolicyNameInDB(oldPolicyName, modifiedPolicyName, tierType);
                                    updatePolicyNameInRegistryFile(oldPolicyName, modifiedPolicyName, tierFile);
                                }
                            } else if (Constants.AM_POLICY_APPLICATION.equals(tierType)) {
                                deployAppThrottlePolicies(policy, tenant, deployPolicies);
                                if (hasSpacesInPolicyName) {
                                    updatePolicyNameInDB(oldPolicyName, modifiedPolicyName, tierType);
                                    updatePolicyNameInRegistryFile(oldPolicyName, modifiedPolicyName, tierFile);
                                }
                            } else if (Constants.AM_API_THROTTLE_POLICY.equals(tierType)) {
                                deployResourceThrottlePolicies(policy, tenant, deployPolicies);
                                if (hasSpacesInPolicyName) {
                                    updateXThrottlingTiersInSwaggerDefinition(oldPolicyName, modifiedPolicyName);
                                    updatePolicyNameInDB(oldPolicyName, modifiedPolicyName, tierType);
                                    updatePolicyNameInRegistryFile(oldPolicyName, modifiedPolicyName, tierFile);
                                }
                            }

                        }
                    }
                }
            } else {
                log.error("Empty content found in " + tierFile + " for tenant " + tenant.getId() + '(' + tenant
                        .getDomain() + ')');
            }

        } catch (UserStoreException ex) {
            log.error("Error occurred while reading Registry for " + tierFile + ", for tenant " + tenant.getId() + '('
                    + tenant.getDomain() + ')', ex);
            throw new APIMigrationException(ex);
        } catch (RegistryException ex) {
            log.error("Error occurred while reading Registry for " + tierFile + ", for tenant " + tenant.getId() + '('
                    + tenant.getDomain() + ')', ex);
            throw new APIMigrationException(ex);
        }
    }

    /**
     * This method update application throttle policies to the database and deploys as execution plans
     * @param policy
     * @param tenant
     * @throws APIManagementException
     * @throws APIMigrationException
     */
    private static void deployAppThrottlePolicies(Policy policy, Tenant tenant, boolean deployPolicies)
            throws APIManagementException, APIMigrationException {
        log.info("Migrating Application throttle policies for " + tenant.getId() + '(' + tenant.getDomain()
                + ')');

        ThrottlePolicyTemplateBuilder policyBuilder = new ThrottlePolicyTemplateBuilder();
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

        boolean needDeployment = false;
        int tenantId = tenant.getId();
        String policyName = policy.getName();

        ApplicationPolicy applicationPolicy = new ApplicationPolicy(policyName);
        applicationPolicy.setDisplayName(policyName);
        applicationPolicy.setDescription(policy.getDescription());
        applicationPolicy.setTenantId(tenantId);
        applicationPolicy.setDeployed(false);
        applicationPolicy.setTenantDomain(tenant.getDomain());
        QuotaPolicy defaultQuotaPolicy = new QuotaPolicy();
        RequestCountLimit requestCountLimit = new RequestCountLimit();
        requestCountLimit.setRequestCount(policy.getMaxCount());
        requestCountLimit.setUnitTime(safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
        requestCountLimit.setTimeUnit(APIConstants.TIME_UNIT_MINUTE);
        defaultQuotaPolicy.setType(PolicyConstants.REQUEST_COUNT_TYPE);
        defaultQuotaPolicy.setLimit(requestCountLimit);
        applicationPolicy.setDefaultQuotaPolicy(defaultQuotaPolicy);

        if (!apiMgtDAO.isPolicyExist(PolicyConstants.POLICY_LEVEL_APP, tenantId, policyName)
                && !APIConstants.DEFAULT_APP_POLICY_UNLIMITED.equalsIgnoreCase(policyName) &&
                !APIConstants.UNAUTHENTICATED_TIER.equalsIgnoreCase(policyName)) {
            apiMgtDAO.addApplicationPolicy(applicationPolicy);
            needDeployment = true;
        }

        if (!apiMgtDAO.isPolicyDeployed(PolicyConstants.POLICY_LEVEL_APP, tenantId, policyName)) {
            needDeployment = true;
        }

        if (needDeployment) {
            String policyString;
            try {
                policyString = policyBuilder.getThrottlePolicyForAppLevel(applicationPolicy);
                String policyFile = applicationPolicy.getTenantDomain() + "_" +PolicyConstants.POLICY_LEVEL_APP +
                        "_" + applicationPolicy.getPolicyName();
                if(!APIConstants.DEFAULT_APP_POLICY_UNLIMITED.equalsIgnoreCase(policyName) &&
                        !APIConstants.UNAUTHENTICATED_TIER.equalsIgnoreCase(policyName)) {
                    if (!deployPolicies) {
                        ResourceUtil.deployPolicy(policyFile, policyString);
                    } else {
                        deployPolicyToGlobalCEP(policyString);
                    }
                }
                apiMgtDAO.setPolicyDeploymentStatus(PolicyConstants.POLICY_LEVEL_APP, applicationPolicy.getPolicyName(),
                        applicationPolicy.getTenantId(), true);
            } catch (APITemplateException e) {
                throw new APIManagementException("Error while adding application policy" + applicationPolicy.getPolicyName(), e);
            }
        }
        log.info("Completed migration of Application throttle policies for " + tenant.getId() + '(' + tenant.getDomain()
                + ')');

    }

    /**
     * This method update resource level throttle policies to the database and deploys as execution plans
     * @param policy
     * @param tenant
     * @throws APIManagementException
     * @throws APIMigrationException
     */
    private static void deployResourceThrottlePolicies(Policy policy, Tenant tenant, boolean deployPolicies)
            throws APIManagementException, APIMigrationException {
        log.info("Migrating Resource throttle policies for " + tenant.getId() + '(' + tenant.getDomain()
                + ')');

        ThrottlePolicyTemplateBuilder policyBuilder = new ThrottlePolicyTemplateBuilder();
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

        boolean needDeployment = false;
        int tenantId = tenant.getId();
        String policyName = policy.getName();

        APIPolicy apiPolicy = new APIPolicy(policyName);
        apiPolicy.setDisplayName(policyName);
        apiPolicy.setDescription(policy.getDescription());
        apiPolicy.setTenantId(tenantId);
        apiPolicy.setUserLevel(APIConstants.API_POLICY_API_LEVEL);
        apiPolicy.setDeployed(false);
        apiPolicy.setTenantDomain(tenant.getDomain());
        QuotaPolicy defaultQuotaPolicy = new QuotaPolicy();
        RequestCountLimit requestCountLimit = new RequestCountLimit();
        requestCountLimit.setRequestCount(policy.getMaxCount());
        requestCountLimit.setUnitTime(safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
        requestCountLimit.setTimeUnit(APIConstants.TIME_UNIT_MINUTE);
        defaultQuotaPolicy.setType(PolicyConstants.REQUEST_COUNT_TYPE);
        defaultQuotaPolicy.setLimit(requestCountLimit);
        apiPolicy.setDefaultQuotaPolicy(defaultQuotaPolicy);

        if (!apiMgtDAO.isPolicyExist(PolicyConstants.POLICY_LEVEL_API, tenantId, policyName) &&
                !APIConstants.DEFAULT_APP_POLICY_UNLIMITED.equalsIgnoreCase(policyName) &&
                !APIConstants.UNAUTHENTICATED_TIER.equalsIgnoreCase(policyName)) {
            apiMgtDAO.addAPIPolicy(apiPolicy);
        }

        if (!apiMgtDAO.isPolicyDeployed(PolicyConstants.POLICY_LEVEL_API, tenantId, policyName)) {
            needDeployment = true;
        }

        if (needDeployment) {
            String policyString;
            try {
                policyString = policyBuilder.getThrottlePolicyForAPILevelDefault(apiPolicy);
                String policyFile = apiPolicy.getTenantDomain() + "_" +PolicyConstants.POLICY_LEVEL_API +
                        "_" + apiPolicy.getPolicyName() + "_default";
                if(!APIConstants.DEFAULT_API_POLICY_UNLIMITED.equalsIgnoreCase(policyName) &&
                        !APIConstants.UNAUTHENTICATED_TIER.equalsIgnoreCase(policyName)) {
                    if (!deployPolicies) {
                        ResourceUtil.deployPolicy(policyFile, policyString);
                    } else {
                        deployPolicyToGlobalCEP(policyString);
                    }
                }
                apiMgtDAO.setPolicyDeploymentStatus(PolicyConstants.POLICY_LEVEL_API, apiPolicy.getPolicyName(),
                        apiPolicy.getTenantId(), true);
            } catch (APITemplateException e) {
                throw new APIManagementException("Error while adding api policy " + apiPolicy.getPolicyName(), e);
            }
        }
        log.info("Completed migration of Resurce throttle policies for " + tenant.getId() + '(' + tenant.getDomain()
                + ')');

    }

    /**
     * This method update subscriptoion throttle policies to the database and deploys as execution plans
     * @param policy
     * @param tenant
     * @throws APIManagementException
     * @throws APIMigrationException
     */
    private static void deploySubscriptionThrottlePolicies(Policy policy, Tenant tenant, boolean deployPolicies)
            throws APIManagementException, APIMigrationException {
        log.info("Migrating Subscription throttle policies for " + tenant.getId() + '(' + tenant.getDomain()
                + ')');
        ThrottlePolicyTemplateBuilder policyBuilder = new ThrottlePolicyTemplateBuilder();
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

        boolean needDeployment = false;
        int tenantId = tenant.getId();
        String policyName = policy.getName();

        SubscriptionPolicy subscriptionPolicy = new SubscriptionPolicy(policyName);
        subscriptionPolicy.setDisplayName(policyName);
        subscriptionPolicy.setDescription(policy.getDescription());
        subscriptionPolicy.setTenantId(tenantId);
        subscriptionPolicy.setDeployed(false);
        subscriptionPolicy.setTenantDomain(tenant.getDomain());
        QuotaPolicy defaultQuotaPolicy = new QuotaPolicy();
        RequestCountLimit requestCountLimit = new RequestCountLimit();
        requestCountLimit.setRequestCount(policy.getMaxCount());
        requestCountLimit.setUnitTime(safeLongToInt(TimeUnit.MILLISECONDS.toMinutes(policy.getUnitTime())));
        requestCountLimit.setTimeUnit(APIConstants.TIME_UNIT_MINUTE);
        defaultQuotaPolicy.setType(PolicyConstants.REQUEST_COUNT_TYPE);
        defaultQuotaPolicy.setLimit(requestCountLimit);
        subscriptionPolicy.setDefaultQuotaPolicy(defaultQuotaPolicy);
        subscriptionPolicy.setStopOnQuotaReach(true);
        if (policy.getBillingPlan() != null) {
            subscriptionPolicy.setBillingPlan(policy.getBillingPlan());
        } else {
            subscriptionPolicy.setBillingPlan(Constants.TIER_BILLING_PLAN_FREE);
        }
        subscriptionPolicy.setCustomAttributes(policy.getCustomAttributes());

        if (!apiMgtDAO.isPolicyExist(PolicyConstants.POLICY_LEVEL_SUB, tenantId, policyName)) {
            apiMgtDAO.addSubscriptionPolicy(subscriptionPolicy);
            needDeployment = true;
        }

        if (!apiMgtDAO.isPolicyDeployed(PolicyConstants.POLICY_LEVEL_SUB, tenantId, policyName)) {
            needDeployment = true;
        }

        if (needDeployment) {
            String policyString;
            try {
                policyString = policyBuilder.getThrottlePolicyForSubscriptionLevel(subscriptionPolicy);
                String policyFile = subscriptionPolicy.getTenantDomain() + "_" +PolicyConstants.POLICY_LEVEL_SUB +
                        "_" + subscriptionPolicy.getPolicyName();
                if(!APIConstants.DEFAULT_SUB_POLICY_UNLIMITED.equalsIgnoreCase(policyName)) {
                    if (!deployPolicies) {
                        ResourceUtil.deployPolicy(policyFile, policyString);
                    } else {
                        deployPolicyToGlobalCEP(policyString);
                    }
                }
                apiMgtDAO.setPolicyDeploymentStatus(PolicyConstants.POLICY_LEVEL_SUB, subscriptionPolicy.getPolicyName(),
                        subscriptionPolicy.getTenantId(), true);
            } catch (APITemplateException e) {
                throw new APIManagementException("Error while adding subscriptioncp  policy " + subscriptionPolicy.getPolicyName(), e);
            }
        }
        log.info("Completed migration of Subscription throttle policies for " + tenant.getId() + '(' + tenant.getDomain()
                + ')');

    }

    /**
     * Populate a policy from the given tier configuration
     * @param tierNode
     * @return
     */
    private Policy readPolicyFromRegistry(Node tierNode) {
        Element tierTag = (Element) tierNode;

        Node throttleID = tierTag.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS, Constants.TIER_ID_TAG).item(0);

        Policy policy = new Policy();
        policy.setName(throttleID.getTextContent());
        policy.setType(((Element)throttleID).getAttribute("throttle:type"));

        Element childPolicy = (Element) tierTag.getElementsByTagNameNS(Constants.TIER_WSP_XMLNS,
                Constants.TIER_POLICY_TAG).item(0);

        Element controlTag = (Element) childPolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                Constants.TIER_CONTROL_TAG)
                .item(0);

        Element controlPolicyTag = (Element) controlTag.getElementsByTagNameNS(Constants.TIER_WSP_XMLNS,
                Constants.TIER_POLICY_TAG)
                .item(0);

        policy.setMaxCount(Integer.valueOf(controlPolicyTag.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                Constants.TIER_MAX_COUNT_TAG)
                .item(0).getTextContent()));


        policy.setUnitTime(Long.valueOf(controlPolicyTag.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                Constants.TIER_UNIT_COUNT_TAG)
                .item(0).getTextContent()));

        Element attributePolicy = (Element) controlPolicyTag.getElementsByTagNameNS(Constants.TIER_WSP_XMLNS,
                Constants.TIER_POLICY_TAG)
                .item(0);

        if (attributePolicy != null)    {
            Element attributeElement = (Element) attributePolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                    Constants.TIER_ATTRIBUTES_TAG).item(0);
            JSONArray customAttrJsonArray = new JSONArray();
            //Read custom attributes
            if (attributeElement != null) {
                NodeList attributes = attributeElement.getChildNodes();
                if (attributes != null) {
                    for (int i =0; i < attributes.getLength(); i++) {
                        if (attributes.item(i).getNodeType() == Node.ELEMENT_NODE) {
                            Element attributeEle = (Element) attributes.item(i);
                            String attributeName = attributeEle.getLocalName();
                            String attributeValue = attributeEle.getTextContent();

                            if (!Constants.TIER_BILLING_PLAN_TAG.equals(attributeName) &&
                                    !Constants.TIER_STOP_ON_QUOTA_TAG.equals(attributeName)
                                    && !Constants.TIER_DESCRIPTION_TAG.equals(attributeName)) {
                                JSONObject attrJsonObj = new JSONObject();
                                attrJsonObj.put("name", attributeName);
                                attrJsonObj.put("value", attributeValue);
                                customAttrJsonArray.add(attrJsonObj);
                            }
                        }
                        policy.setCustomAttributes(customAttrJsonArray.toJSONString().getBytes());
                    }
                }
            }
            Node billingPlan = attributePolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                    Constants.TIER_BILLING_PLAN_TAG).item(0);

            if (billingPlan != null) {
                policy.setBillingPlan(billingPlan.getTextContent());
            }

            Node stopOnQuotaReach = attributePolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                    Constants.TIER_STOP_ON_QUOTA_TAG).item(0);
            if (stopOnQuotaReach != null) {
                policy.setStopOnQuotaReach(Boolean.valueOf(stopOnQuotaReach.getTextContent()));
            }

            Node description = attributePolicy.getElementsByTagNameNS(Constants.TIER_THROTTLE_XMLNS,
                    Constants.TIER_DESCRIPTION_TAG).item(0);
            if (description != null) {
                policy.setDescription(description.getTextContent());
            }
        }

        //setting policy description if it is not set already
        if (policy.getDescription() == null) {
            policy.setDescription(MessageFormat.format(Constants.TIER_DESCRIPTION, policy.getMaxCount()));
        }

        return policy;

    }

    public static int safeLongToInt(long l) {
        if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
            throw new IllegalArgumentException
                    (l + " cannot be cast to int without changing its value.");
        }
        return (int) l;
    }

    /**
     * This method will be used to deploy policy to Global policy engine.
     *
     * @param policy     Policy string to be deployed.
     * @throws APIManagementException
     */
    private static void deployPolicyToGlobalCEP(String policy) throws APIManagementException {
        try {
            if (globalThrottleEngineClient == null) {
                globalThrottleEngineClient = new GlobalThrottleEngineClient();
            }
            globalThrottleEngineClient.deployExecutionPlan(policy);
        } catch (Exception e) {
            log.error("Error while deploying policy to global policy server." + e.getMessage());
            throw new APIManagementException("Error while deploying policy to global policy server. " +  e.getMessage());
        }
    }

    /**
     *This method will be used to populate SP_APP table
     */
    public void populateSPAPPs() throws APIMigrationException {

    }

    @Override
    public void userRolesMigration() throws APIMigrationException {
    }

    /**
     * This method updates a policy name
     *
     * @param oldPolicyName      policy name to be modified
     * @param modifiedPolicyName modified policy name
     * @return no of rows affected by update
     * @throws APIManagementException
     */
    private int updatePolicyNameInDB(String oldPolicyName, String modifiedPolicyName, String tierType)
            throws APIManagementException {
        Connection connection = null;
        PreparedStatement prepStmt = null;
        String userId = null;

        String sqlQuery = "";
        if (Constants.AM_POLICY_SUBSCRIPTION.equals(tierType)) {
            sqlQuery = "UPDATE AM_SUBSCRIPTION SET TIER_ID = ? WHERE TIER_ID = ?";
        } else if (Constants.AM_POLICY_APPLICATION.equals(tierType)) {
            sqlQuery = "UPDATE AM_APPLICATION SET APPLICATION_TIER = ? WHERE APPLICATION_TIER = ?";
        } else if (Constants.AM_API_THROTTLE_POLICY.equals(tierType)) {
            sqlQuery = "UPDATE AM_API_URL_MAPPING SET THROTTLING_TIER = ? WHERE THROTTLING_TIER = ?";
        }

        int status = 0;
        try {
            connection = APIMgtDBUtil.getConnection();
            connection.setAutoCommit(false);
            prepStmt = connection.prepareStatement(sqlQuery);
            prepStmt.setString(1, modifiedPolicyName);
            prepStmt.setString(2, oldPolicyName);
            status = prepStmt.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            String msg = "Error while modifying subscription policy ID";
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(prepStmt, connection, null);
        }
        return status;
    }

    /**
     * This method returns API ID set of all APIs that contain a URL_MAPPING that has the specified tier
     * @param tierName  tier name
     * @return
     */
    private List<APIIdentifier> getAPIsToBeModified(String tierName) throws APIManagementException {
        Connection connection = null;
        PreparedStatement prepStmt = null;
        ResultSet rs = null;
        String userId = null;
        List<APIIdentifier> apiList = new ArrayList<APIIdentifier>();

        String sqlQuery = "SELECT AM_API.API_NAME, AM_API.API_VERSION, AM_API.API_PROVIDER FROM AM_API_URL_MAPPING "
                + "INNER JOIN AM_API ON AM_API_URL_MAPPING.API_ID = AM_API.API_ID WHERE THROTTLING_TIER=? GROUP BY "
                + "AM_API.API_ID";
        try {
            connection = APIMgtDBUtil.getConnection();
            prepStmt = connection.prepareStatement(sqlQuery);
            prepStmt.setString(1, tierName);
            rs = prepStmt.executeQuery();
            while (rs.next()) {
                String apiName = rs.getString("API_NAME");
                String apiVersion = rs.getString("API_VERSION");
                String apiProvider = rs.getString("API_PROVIDER").replace("@", "-AT-");

                apiList.add(new APIIdentifier(apiProvider, apiName, apiVersion));
            }
        } catch (SQLException e) {
            String msg = "Error while fetching APIs that need resource level tier name modifications";
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(prepStmt, connection, null);
        }
        return apiList;
    }

    /**
     * This method modifies x-throttling-tier values to modifiedTierName in api swagger.json files
     * @param oldTierName
     * @param modifiedTierName
     */
    private void updateXThrottlingTiersInSwaggerDefinition(String oldTierName, String modifiedTierName) throws APIManagementException {
        List<APIIdentifier> apiList = getAPIsToBeModified(oldTierName);
        try {
            for (int i = 0; i < apiList.size(); i++) {
                APIIdentifier api = apiList.get(i);
                String apiName = api.getApiName();
                String apiVersion = api.getVersion();
                String apiProvider = api.getProviderName();

                String swaggerlocation = ResourceUtil.getSwagger2ResourceLocation(apiName, apiVersion, apiProvider);
                Object rawResource = registryService.getGovernanceRegistryResource(swaggerlocation);
                String swaggerDocument = null;
                if (rawResource != null) {
                    swaggerDocument = ResourceUtil.getResourceContent(rawResource);
                    swaggerDocument = swaggerDocument.replace(oldTierName, modifiedTierName);
                }
                registryService.addGovernanceRegistryResource(swaggerlocation, swaggerDocument, "application/json");

            }
        } catch (UserStoreException e) {
            String msg = "Error while retrieving registry resource";
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        } catch (RegistryException e) {
            String msg = "Error while retrieving registry resource";
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        }
    }

    /**
     * This method modifies overview_tier filed in api rxt to not contain spaces in tierName
     * @throws APIManagementException
     */
    private void modifySubscriptionPolicyNamesInApiRXT() throws APIManagementException {
        GenericArtifact[] genericArtifacts = registryService.getGenericAPIArtifacts();
        GenericArtifact[] modifiedArtifacts = new GenericArtifact[genericArtifacts.length];
        String[] apiTiersList;
        String tier;
        int index = 0;
        for (GenericArtifact genericArtifact : genericArtifacts) {
            StringBuilder modifiedApiTiers = new StringBuilder();
            try {
                String availableTiers = genericArtifact.getAttribute(APIConstants.API_OVERVIEW_TIER);
                if (availableTiers != null) {
                    apiTiersList = availableTiers.split(Pattern.quote("||"));
                    if (apiTiersList.length > 0) {
                        for (int i = 0; i < apiTiersList.length; i++) {
                            tier = apiTiersList[i].trim().replace(" ", "_");
                            modifiedApiTiers.append(tier + "||");
                        }
                        genericArtifact.setAttribute(APIConstants.API_OVERVIEW_TIER,
                                modifiedApiTiers.substring(0, modifiedApiTiers.length() - 2));
                    }
                }
            } catch (GovernanceException e) {
                String msg = "Error while updating API RXTs";
                log.error(msg, e);
                throw new APIManagementException(msg, e);
            }
            modifiedArtifacts[index] = genericArtifact;
            index++;
        }
        registryService.updateGenericAPIArtifacts(modifiedArtifacts);
    }

    /**
     * This method modifes policy name to modifedPolicyName in specified file
     * @param oldPolicyName         policyName to be replaced
     * @param modifiedPolicyName    new policyName
     * @param fileLocation          registry path of the file to be modified
     * @throws APIManagementException
     */
    private void updatePolicyNameInRegistryFile(String oldPolicyName, String modifiedPolicyName, String fileLocation)
            throws APIManagementException {
        try {
            Object rawResource = registryService.getGovernanceRegistryResource(fileLocation);
            String fileContent = ResourceUtil.getResourceContent(rawResource);
            String modifiedFileContent = fileContent.replace(oldPolicyName, modifiedPolicyName);
            registryService.updateGovernanceRegistryResource(fileLocation, modifiedFileContent);
        } catch (UserStoreException e) {
            String msg = "Error while updating file at " + fileLocation;
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        } catch (RegistryException e) {
            String msg = "Error while updating file at " + fileLocation;
            log.error(msg, e);
            throw new APIManagementException(msg, e);
        }
    }
}
