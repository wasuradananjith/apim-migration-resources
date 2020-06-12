/*
*  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
*  WSO2 Inc. licenses this file to you under the Apache License,
*  Version 2.0 (the "License"); you may not use this file except
*  in compliance with the License.
*  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package org.wso2.carbon.apimgt.migration.dao;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dto.APIKeyInfoDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.migration.dto.UserRoleFromPermissionDTO;
import org.wso2.carbon.apimgt.migration.util.Constants;
import org.wso2.carbon.apimgt.migration.util.SharedDBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represent the SharedDAO.
 */
public class SharedDAO {
    private static final Log log = LogFactory.getLog(SharedDAO.class);
    private static SharedDAO INSTANCE = null;

    private boolean forceCaseInsensitiveComparisons = false;
    private boolean multiGroupAppSharingEnabled = false;
    private static boolean initialAutoCommit = false;

    private SharedDAO() {
    }

    public UserRoleFromPermissionDTO[] getRoleNamesMatchingPermission(String permission, int tenantId) throws APIManagementException {
        UserRoleFromPermissionDTO[] userRoleFromPermissionDTOs = null;
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet resultSet = null;
        List<UserRoleFromPermissionDTO> userRoleFromPermissionList = new ArrayList<UserRoleFromPermissionDTO>();

        String sqlQuery =
            " SELECT " +
            "   UM_ROLE_NAME, UM_DOMAIN_NAME " +
            " FROM "+
            "   UM_ROLE_PERMISSION " +
            " INNER JOIN "+
            "   UM_PERMISSION " +
            " ON "+
            "   UM_ROLE_PERMISSION.UM_PERMISSION_ID=UM_PERMISSION.UM_ID " +
            " INNER JOIN " +
            "   UM_DOMAIN " +
            " ON " +
            "   UM_ROLE_PERMISSION.UM_DOMAIN_ID=UM_DOMAIN.UM_DOMAIN_ID " +
            " WHERE " +
            "   UM_RESOURCE_ID = ? AND UM_ROLE_PERMISSION.UM_TENANT_ID = ?";

        try {
            conn = SharedDBUtil.getConnection();
            ps = conn.prepareStatement(sqlQuery);
            ps.setString(1, permission);
            ps.setString(2, Integer.toString(tenantId));
            resultSet = ps.executeQuery();
            while (resultSet.next()) {
                String userRoleName = resultSet.getString(Constants.UM_ROLE_NAME);
                String userRoleDomainName = resultSet.getString(Constants.UM_DOMAIN_NAME);
                UserRoleFromPermissionDTO userRoleFromPermissionDTO = new UserRoleFromPermissionDTO();
                userRoleFromPermissionDTO.setUserRoleName(userRoleName);
                userRoleFromPermissionDTO.setUserRoleDomainName(userRoleDomainName);
                userRoleFromPermissionList.add(userRoleFromPermissionDTO);
            }
            userRoleFromPermissionDTOs = userRoleFromPermissionList.toArray(new UserRoleFromPermissionDTO[userRoleFromPermissionList.size()]);
        } catch (SQLException e) {
            handleException("Failed to get Roles matching the permission" + permission, e);
        } finally {
            SharedDBUtil.closeAllConnections(ps, conn, resultSet);
        }
        return userRoleFromPermissionDTOs;
    }

    private void handleException(String msg, Throwable t) throws APIManagementException {
        log.error(msg, t);
        throw new APIManagementException(msg, t);
    }

    /**
     * Method to get the instance of the SharedDAO.
     *
     * @return {@link SharedDAO} instance
     */
    public static SharedDAO getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new SharedDAO();
        }

        return INSTANCE;
    }
}
