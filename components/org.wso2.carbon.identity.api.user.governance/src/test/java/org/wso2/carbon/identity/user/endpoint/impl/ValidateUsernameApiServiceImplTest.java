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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.identity.user.endpoint.impl;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.mgt.constants.SelfRegistrationStatusCodes;
import org.wso2.carbon.identity.recovery.IdentityRecoveryException;
import org.wso2.carbon.identity.recovery.internal.IdentityRecoveryServiceDataHolder;
import org.wso2.carbon.identity.recovery.signup.UserSelfRegistrationManager;
import org.wso2.carbon.identity.user.endpoint.dto.ErrorDTO;
import org.wso2.carbon.identity.user.endpoint.dto.PropertyDTO;
import org.wso2.carbon.identity.user.endpoint.dto.UsernameValidationRequestDTO;
import org.wso2.carbon.identity.user.endpoint.util.Utils;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.core.service.RealmService;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.ArrayList;
import java.util.List;
import javax.ws.rs.core.Response;

/**
 * This is a test class for {@link ValidateUsernameApiServiceImpl}.
 */
public class ValidateUsernameApiServiceImplTest {

    private MockedStatic<Utils> mockedUtils;
    private MockedStatic<IdentityUtil> mockedIdentityUtil;

    @InjectMocks
    private ValidateUsernameApiServiceImpl validateUsernameApiService;

    @Mock
    private UserSelfRegistrationManager userSelfRegistrationManager;

    @Test (description = "This test case tests the behaviour of the API when username is empty.")
    public void testEmptyUserName() {

        UsernameValidationRequestDTO usernameValidationRequestDTO = new UsernameValidationRequestDTO();
        Assert.assertEquals(validateUsernameApiService.validateUsernamePost(usernameValidationRequestDTO).getStatus(),
                Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test(description = "This test method checks the behaviour of validateUserNameApi when self signup is false.")
    public void testSelfSignUpDisabled() {

        UsernameValidationRequestDTO usernameValidationRequestDTO = new UsernameValidationRequestDTO();
        usernameValidationRequestDTO.setUsername("test");
        ErrorDTO errorDTO = (ErrorDTO)
                validateUsernameApiService.validateUsernamePost(usernameValidationRequestDTO).getEntity();
        Assert.assertEquals(errorDTO.getCode(), SelfRegistrationStatusCodes.ERROR_CODE_SELF_REGISTRATION_DISABLED,
                "Expected error code is not received.");
    }

    @Test(description = "This test method checks the behaviour of validateUserNameApi when self signup is true.")
    public void testSelfSignUpUserExists() {

        UsernameValidationRequestDTO usernameValidationRequestDTO = new UsernameValidationRequestDTO();
        usernameValidationRequestDTO.setUsername("test");
        List<PropertyDTO> propertyDTOList = new ArrayList<>();
        PropertyDTO propertyDTO = new PropertyDTO();
        propertyDTO.setKey("skipSignUpEnableCheck");
        propertyDTO.setValue("true");
        propertyDTOList.add(propertyDTO);
        usernameValidationRequestDTO.setProperties(propertyDTOList);

        ErrorDTO errorDTO = (ErrorDTO)
                validateUsernameApiService.validateUsernamePost(usernameValidationRequestDTO).getEntity();
        Assert.assertEquals(errorDTO.getCode(), SelfRegistrationStatusCodes.ERROR_CODE_USER_ALREADY_EXISTS,
                "Expected error code is not received.");
    }

    @Test(description = "Test that the error message is fetched from the secondary user store.")
    public void testRegexViolationErrorMsgFromSecondaryUserStore() throws Exception {

        mockedIdentityUtil.when(() -> IdentityUtil.extractDomainFromName("invalidUsername")).thenReturn("SECONDARY");

        RealmService mockedRealmService = setupRealmServiceMocks();
        org.wso2.carbon.user.core.UserStoreManager mockedPrimaryUserStoreManager = setupPrimaryUserStoreManager(mockedRealmService);
        setupSecondaryUserStoreManager(mockedPrimaryUserStoreManager);

        injectMockedRealmService(mockedRealmService);

        UsernameValidationRequestDTO usernameValidationRequestDTO = createRequestDTO("invalidUsername", "SECONDARY");

        mockSelfRegistrationEnabled(true);
        mockRegexValidationFailure("SECONDARY/invalidUsername");

        Response response = validateUsernameApiService.validateUsernamePost(usernameValidationRequestDTO);

        Assert.assertEquals(response.getStatus(), Response.Status.BAD_REQUEST.getStatusCode(), "Unexpected response status.");

        ErrorDTO errorDTO = (ErrorDTO) response.getEntity();
        Assert.assertEquals(errorDTO.getMessage(), "Secondary user store regex violation message",
                "Unexpected error message. Expected secondary user store message.");
    }

    @BeforeMethod
    private void init() throws IdentityRecoveryException {

        MockitoAnnotations.openMocks(this);
        mockedUtils = Mockito.mockStatic(Utils.class);
        mockedUtils.when(Utils::getUserSelfRegistrationManager).thenReturn(userSelfRegistrationManager);
        mockedIdentityUtil = Mockito.mockStatic(IdentityUtil.class);
        mockedIdentityUtil.when(IdentityUtil::getPrimaryDomainName).thenReturn("PRIMARY");
        Mockito.doReturn(true).when(userSelfRegistrationManager)
                .isValidTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        Mockito.doReturn(false).when(userSelfRegistrationManager)
                .isSelfRegistrationEnabled(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        Mockito.doReturn(true).when(userSelfRegistrationManager)
                .isUsernameAlreadyTaken("test", MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
    }

    @AfterMethod
    public void tearDown() {

        mockedUtils.close();
        mockedIdentityUtil.close();
    }

    private RealmService setupRealmServiceMocks() throws Exception {

        RealmService mockedRealmService = Mockito.mock(RealmService.class);
        org.wso2.carbon.user.core.tenant.TenantManager mockedTenantManager =
                Mockito.mock(org.wso2.carbon.user.core.tenant.TenantManager.class);
        org.wso2.carbon.user.core.UserRealm mockedUserRealm =
                Mockito.mock(org.wso2.carbon.user.core.UserRealm.class);

        Mockito.when(mockedRealmService.getTenantManager()).thenReturn(mockedTenantManager);
        Mockito.when(mockedTenantManager.getTenantId(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME)).thenReturn(1);
        Mockito.when(mockedRealmService.getTenantUserRealm(1)).thenReturn(mockedUserRealm);

        return mockedRealmService;
    }

    private org.wso2.carbon.user.core.UserStoreManager setupPrimaryUserStoreManager(RealmService mockedRealmService)
            throws Exception {

        org.wso2.carbon.user.core.UserRealm mockedUserRealm =
                (org.wso2.carbon.user.core.UserRealm) mockedRealmService.getTenantUserRealm(1);
        org.wso2.carbon.user.core.UserStoreManager mockedPrimaryUserStoreManager =
                Mockito.mock(org.wso2.carbon.user.core.UserStoreManager.class);
        RealmConfiguration primaryRealmConfiguration = Mockito.mock(RealmConfiguration.class);

        Mockito.when(mockedUserRealm.getUserStoreManager()).thenReturn(mockedPrimaryUserStoreManager);
        Mockito.when(mockedPrimaryUserStoreManager.getRealmConfiguration()).thenReturn(primaryRealmConfiguration);
        Mockito.when(primaryRealmConfiguration.getUserStoreProperty("UsernameJavaRegExViolationErrorMsg"))
                .thenReturn("Primary user store regex violation message");

        return mockedPrimaryUserStoreManager;
    }

    private void setupSecondaryUserStoreManager(org.wso2.carbon.user.core.UserStoreManager
                                                        mockedPrimaryUserStoreManager) {

        org.wso2.carbon.user.core.UserStoreManager mockedSecondaryUserStoreManager =
                Mockito.mock(org.wso2.carbon.user.core.UserStoreManager.class);
        RealmConfiguration secondaryRealmConfiguration = Mockito.mock(RealmConfiguration.class);

        Mockito.when(mockedPrimaryUserStoreManager.getSecondaryUserStoreManager("SECONDARY"))
                .thenReturn(mockedSecondaryUserStoreManager);
        Mockito.when(mockedSecondaryUserStoreManager.getRealmConfiguration()).thenReturn(secondaryRealmConfiguration);
        Mockito.when(secondaryRealmConfiguration.getUserStoreProperty("UsernameJavaRegExViolationErrorMsg"))
                .thenReturn("Secondary user store regex violation message");
    }

    private void injectMockedRealmService(RealmService mockedRealmService) {

        IdentityRecoveryServiceDataHolder.getInstance().setRealmService(mockedRealmService);
    }

    private UsernameValidationRequestDTO createRequestDTO(String username, String realm) {

        UsernameValidationRequestDTO requestDTO = new UsernameValidationRequestDTO();
        requestDTO.setUsername(username);

        PropertyDTO realmProperty = new PropertyDTO();
        realmProperty.setKey("realm");
        realmProperty.setValue(realm);

        List<PropertyDTO> propertyDTOList = new ArrayList<>();
        propertyDTOList.add(realmProperty);

        requestDTO.setProperties(propertyDTOList);

        return requestDTO;
    }

    private void mockSelfRegistrationEnabled(boolean isEnabled) throws IdentityRecoveryException {

        Mockito.doReturn(isEnabled).when(userSelfRegistrationManager)
                .isSelfRegistrationEnabled(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
    }

    private void mockRegexValidationFailure(String usernameWithDomain) throws IdentityRecoveryException {

        Mockito.doReturn(false).when(userSelfRegistrationManager)
                .isMatchUserNameRegex(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME, usernameWithDomain);
    }
}
