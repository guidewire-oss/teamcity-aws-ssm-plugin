package com.guidewire.agent;

import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildParametersMap;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.PasswordReplacer;
import org.junit.Test;
import org.junit.Before;
import org.mockito.*;

import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.internal.matchers.Any;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class AwsSsmEnvParameterReplacerTest {
    private static final Logger log = LoggerFactory.getLogger(AwsSsmEnvParameterReplacerTest.class);

    @Mock
    private AgentRunningBuild runningBuild;

    @Mock
    private BuildProgressLogger buildProgressLogger;

    @Mock
    private SsmClient ssmClient;

    private PasswordReplacer passwordReplacer;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(runningBuild.getBuildLogger()).thenReturn(buildProgressLogger);
        passwordReplacer = mock(PasswordReplacer.class);
        when(runningBuild.getPasswordReplacer()).thenReturn(passwordReplacer);
    }

    @Test
    public void testUpdateBuildParameters() {
        // Mock dependencies
        AgentRunningBuild mockRunningBuild = mock(AgentRunningBuild.class);
        BuildProgressLogger mockLogger = mock(BuildProgressLogger.class);
        SsmClient mockSsmClient = mock(SsmClient.class);
        // Stub methods
        when(mockRunningBuild.getBuildLogger()).thenReturn(mockLogger);
        when(mockRunningBuild.getSharedConfigParameters()).thenReturn(new HashMap<String, String>() {{
            put("aws_ssm_access_key_id", "fakeAccessKey");
            put("aws_ssm_secret_access_key", "fakeSecretKey");
            put("aws_region", "fakeRegion");
            put("test_build_param_1", "%aws-ssm:fake-param1%");
        }});

        BuildParametersMap mockBuildParametersMap = mock(BuildParametersMap.class);
        when(mockRunningBuild.getSharedBuildParameters()).thenReturn(mockBuildParametersMap);
        when(mockBuildParametersMap.getAllParameters()).thenReturn(new HashMap<String, String>() {{
            put("env.test_build_param_2", "%aws-ssm:fake-param2%");
            put("system.test_build_param_3", "fake-param3");
        }});

        GetParameterResponse mockResponse = Mockito.mock(GetParameterResponse.class);
        when(mockSsmClient.getParameter(any(GetParameterRequest.class))).thenReturn(mockResponse);

        Parameter mockParameter = mock(Parameter.class);
        when(mockResponse.parameter()).thenReturn(mockParameter);
        when(mockParameter.value()).thenReturn("ssm-value");


        // Invoke the method under test
        AwsSsmEnvParameterReplacer.updateBuildParameters(mockRunningBuild);

        // Verify interactions and expected outcomes
        verify(mockLogger, times(1)).message("teamcity-aws-ssm-plugin starting...");
        verify(mockLogger, times(1)).message("Detected ssm parameters: [test_build_param_1, env.test_build_param_2]");
        verify(mockLogger, times(1)).message("Requesting ssm parameter value for test_build_param_1");
        verify(mockLogger, times(1)).message("teamcity-aws-ssm-plugin finished");
    }

    @Test
    public void testGetSsmParametersSuccess() throws Exception {
        // Setup
        Map<String, String> tcParameters = new HashMap<>();
        tcParameters.put("key1", "%aws-ssm:param1%");
        tcParameters.put("key2", "%aws-ssm:param2%");
        tcParameters.put("key3", "no_match_param");
        BuildProgressLogger buildProgressLogger = Mockito.mock(BuildProgressLogger.class);

        // Expected result
        Map<String, String> expectedResult = new HashMap<>();
        expectedResult.put("key1", "param1");
        expectedResult.put("key2", "param2");

        // Invoke function
        Map<String, String> result = AwsSsmEnvParameterReplacer.getSsmParameters(tcParameters, buildProgressLogger);

        // Verify
        assertEquals(expectedResult, result);
        Mockito.verify(buildProgressLogger).message("Detected ssm parameters: [key1, key2]");
    }

    @Test
    public void testReplaceBuildParams() throws Exception {
        Map<String, String> ssmParams = new HashMap<>();
        ssmParams.put("env.VAR1", "value1");
        ssmParams.put("system.VAR2", "value2");
        ssmParams.put("VAR3", "value3");
        AwsSsmEnvParameterReplacer.replaceBuildParams(runningBuild, ssmParams);
        verify(runningBuild, times(1)).addSharedEnvironmentVariable("VAR1", "value1");
        verify(runningBuild, times(1)).addSharedSystemProperty("VAR2", "value2");
        verify(runningBuild, times(1)).addSharedConfigParameter("VAR3", "value3");
        verify(passwordReplacer, times(1)).addPassword("value1");
        verify(passwordReplacer, times(1)).addPassword("value2");
        verify(passwordReplacer, times(1)).addPassword("value3");
    }



}


